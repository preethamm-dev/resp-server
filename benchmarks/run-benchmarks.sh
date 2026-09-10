#!/usr/bin/env bash
#
# Benchmarks three subjects under identical conditions:
#
#   1. resp-server, virtual-thread-per-connection mode
#   2. resp-server, single-threaded NIO event-loop mode
#   3. real Redis, whatever version is installed
#
# Measurement uses the official redis-benchmark rather than a harness of my own.
# A self-written load generator invites the obvious suspicion, and this way the
# same third-party tool measures all three subjects the same way.
#
# Everything runs natively on one host over loopback. Containerising all three
# would also be fair, but it would add networking overhead to every measurement
# for no gain in comparability.
#
# Usage: ./benchmarks/run-benchmarks.sh [runs]

set -u

RUNS="${1:-3}"
OUT_DIR="benchmarks/results"
RAW_DIR="$OUT_DIR/raw"
JAR="target/resp-server.jar"

VT_PORT=7001
EL_PORT=7002
REDIS_PORT=7003

if [ ! -f "$JAR" ]; then
  echo "error: $JAR not found -- run 'mvn package' first" >&2
  exit 1
fi
for tool in redis-benchmark redis-server redis-cli java; do
  command -v "$tool" >/dev/null || { echo "error: $tool not on PATH" >&2; exit 1; }
done

mkdir -p "$RAW_DIR"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  sleep 0.3
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
}
trap cleanup EXIT

wait_for_port() {
  for _ in $(seq 1 100); do
    redis-cli -p "$1" PING >/dev/null 2>&1 && return 0
    sleep 0.1
  done
  echo "error: nothing answering on port $1" >&2
  return 1
}

start_resp_server() {   # mode, port
  java -XX:+UseG1GC -jar "$JAR" --mode "$1" --port "$2" >/dev/null 2>&1 &
  PIDS+=($!)
  wait_for_port "$2"
}

start_redis() {
  redis-server --port "$REDIS_PORT" --save '' --appendonly no \
    --daemonize no --loglevel warning >/dev/null 2>&1 &
  PIDS+=($!)
  wait_for_port "$REDIS_PORT"
}

# ---------------------------------------------------------------------------
# environment -- recorded so the numbers mean something to a reader later
# ---------------------------------------------------------------------------
ENV_FILE="$OUT_DIR/environment.txt"
{
  echo "date            : $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
  echo "kernel          : $(uname -sr)"
  echo "distro          : $(. /etc/os-release && echo "$PRETTY_NAME")"
  echo "cpu             : $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)"
  echo "cpu cores       : $(nproc)"
  echo "memory          : $(free -h | awk '/^Mem:/ {print $2}')"
  echo "java            : $(java -version 2>&1 | head -1)"
  echo "jvm flags       : -XX:+UseG1GC"
  echo "redis-server    : $(redis-server --version | cut -d' ' -f1-3)"
  echo "redis-benchmark : $(redis-benchmark --version)"
  echo "resp-server     : $(git describe --always --dirty 2>/dev/null || echo unknown)"
  echo "runs per config : $RUNS (plus one discarded warmup)"
  echo "transport       : loopback, all subjects native on one host"
} > "$ENV_FILE"

echo "=== environment ==="
cat "$ENV_FILE"

# ---------------------------------------------------------------------------
# configurations
# ---------------------------------------------------------------------------
# name | redis-benchmark arguments
CONFIGS=(
  "mixed-c50|-t ping,set,get,incr,lpush,rpop,sadd,hset,zadd,mset -n 100000 -c 50 -P 1"
  "pipelined-P16|-t set,get -n 500000 -c 50 -P 16"
  "connections-c500|-t set,get -n 100000 -c 500 -P 1"
)

SUBJECTS=("virtual|$VT_PORT" "eventloop|$EL_PORT" "redis|$REDIS_PORT")

echo
echo "=== starting subjects ==="
start_resp_server virtual "$VT_PORT"   && echo "  resp-server virtual   :$VT_PORT"
start_resp_server eventloop "$EL_PORT" && echo "  resp-server eventloop :$EL_PORT"
start_redis                             && echo "  redis-server          :$REDIS_PORT"

# ---------------------------------------------------------------------------
# warmup -- discarded
# ---------------------------------------------------------------------------
# The JVM starts interpreted and only compiles hot methods after a few thousand
# invocations, so a first measured run would be timing the interpreter and the
# JIT rather than the server. Redis gets the same warmup for symmetry even
# though a C binary does not need one.
echo
echo "=== warmup (discarded) ==="
for subject in "${SUBJECTS[@]}"; do
  name="${subject%%|*}"; port="${subject##*|}"
  printf '  %-10s ' "$name"
  redis-benchmark -p "$port" -t set,get -n 200000 -c 50 -P 8 -q >/dev/null 2>&1
  redis-benchmark -p "$port" -t set,get -n 100000 -c 50 -q >/dev/null 2>&1
  echo "done"
done

# ---------------------------------------------------------------------------
# measured runs
# ---------------------------------------------------------------------------
CSV="$OUT_DIR/results.csv"
echo "config,subject,run,test,rps,avg_ms,min_ms,p50_ms,p95_ms,p99_ms,max_ms" > "$CSV"

for config in "${CONFIGS[@]}"; do
  cfg_name="${config%%|*}"
  cfg_args="${config##*|}"
  echo
  echo "=== $cfg_name : redis-benchmark $cfg_args ==="

  for run in $(seq 1 "$RUNS"); do
    for subject in "${SUBJECTS[@]}"; do
      name="${subject%%|*}"; port="${subject##*|}"
      raw="$RAW_DIR/${cfg_name}_${name}_run${run}.csv"

      printf '  run %d  %-10s ' "$run" "$name"
      redis-cli -p "$port" FLUSHALL >/dev/null 2>&1

      # shellcheck disable=SC2086
      redis-benchmark -p "$port" $cfg_args --csv > "$raw" 2>/dev/null

      # redis-benchmark --csv emits a header row plus one row per test.
      tail -n +2 "$raw" | tr -d '"' | while IFS=, read -r test rps avg min p50 p95 p99 max; do
        [ -n "${test:-}" ] || continue
        echo "$cfg_name,$name,$run,$test,$rps,$avg,$min,$p50,$p95,$p99,$max" >> "$CSV"
      done
      echo "ok"
    done
  done
done

echo
echo "=== raw output : $RAW_DIR/ ==="
echo "=== combined   : $CSV ==="
echo "=== environment: $ENV_FILE ==="
echo
echo "Summarise with: python3 benchmarks/summarise.py"
