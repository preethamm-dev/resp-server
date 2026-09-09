#!/usr/bin/env bash
#
# Proves durability the only way that really counts: kill the server with SIGKILL
# while it is mid-write, restart it, and check that nothing acknowledged was lost.
#
# SIGKILL cannot be caught, so no shutdown hook runs, no buffer is flushed and no
# file is closed. Whatever survives does so because it was already forced to disk.
#
# Usage:  ./scripts/crash-recovery-demo.sh [port]
# Needs:  a built jar (mvn package) and redis-cli on PATH.

set -u

PORT="${1:-6381}"
AOF="$(mktemp -d)/appendonly.aof"
LOG="$(mktemp)"
JAR="target/resp-server.jar"

if [ ! -f "$JAR" ]; then
  echo "error: $JAR not found -- run 'mvn package' first" >&2
  exit 1
fi

cleanup() {
  [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null
  [ -n "${PID2:-}" ] && kill "$PID2" 2>/dev/null
  rm -rf "$(dirname "$AOF")" "$LOG"
}
trap cleanup EXIT

start_server() {
  java -jar "$JAR" --port "$PORT" --appendonly "$AOF" --fsync always >> "$LOG" 2>&1 &
  local pid=$!
  for _ in $(seq 1 60); do
    redis-cli -p "$PORT" PING >/dev/null 2>&1 && break
    sleep 0.1
  done
  echo "$pid"
}

# A killed process lingers as a zombie until it is reaped, and `kill -0` succeeds on a
# zombie -- so check the actual process state rather than merely its existence.
is_running() {
  local state
  state=$(ps -o state= -p "$1" 2>/dev/null | tr -d ' ')
  [ -n "$state" ] && [ "$state" != "Z" ]
}

echo "=== 1. start with AOF persistence, fsync on every write ==="
PID=$(start_server)
grep -E 'listening|aof' "$LOG"

echo
echo "=== 2. write 5000 keys and one value of every type ==="
redis-cli -p "$PORT" FLUSHALL >/dev/null
for i in $(seq 1 5000); do echo "SET key:$i value:$i"; done \
  | redis-cli -p "$PORT" --pipe 2>&1 | tail -1
redis-cli -p "$PORT" RPUSH queue a b c   >/dev/null
redis-cli -p "$PORT" HSET profile name preetham >/dev/null
redis-cli -p "$PORT" SADD tags java redis >/dev/null
redis-cli -p "$PORT" ZADD board 42 player1 >/dev/null

BEFORE=$(redis-cli -p "$PORT" DBSIZE)
echo "    keys acknowledged : $BEFORE"
echo "    aof size          : $(stat -c %s "$AOF") bytes"

echo
echo "=== 3. SIGKILL the server while more writes are still in flight ==="
( for i in $(seq 5001 500000); do echo "SET key:$i value:$i"; done \
    | redis-cli -p "$PORT" --pipe >/dev/null 2>&1 ) &
WRITER=$!
sleep 0.7
kill -9 "$PID" 2>/dev/null
kill "$WRITER" 2>/dev/null
sleep 0.3
echo "    killed pid $PID with SIGKILL -- no hook, no flush, no close"
echo "    still running     : $(is_running "$PID" && echo yes || echo no)"
echo "    aof size          : $(stat -c %s "$AOF") bytes"

echo
echo "=== 4. restart ==="
PID2=$(start_server)
grep -E 'restored|discarded' "$LOG" || echo "    (nothing to recover)"

echo
echo "=== 5. verify ==="
AFTER=$(redis-cli -p "$PORT" DBSIZE)
echo "    keys before crash : $BEFORE"
echo "    keys after restart: $AFTER"
echo "    key:1             : $(redis-cli -p "$PORT" GET key:1)"
echo "    key:5000          : $(redis-cli -p "$PORT" GET key:5000)"
echo "    queue             : $(redis-cli -p "$PORT" LRANGE queue 0 -1 | paste -sd,)"
echo "    profile.name      : $(redis-cli -p "$PORT" HGET profile name)"
echo "    tags              : $(redis-cli -p "$PORT" SMEMBERS tags | sort | paste -sd,)"
echo "    board score       : $(redis-cli -p "$PORT" ZSCORE board player1)"

MISSING=0
SAMPLED=0
for i in $(seq 1 250 5000); do
  SAMPLED=$((SAMPLED + 1))
  [ "$(redis-cli -p "$PORT" GET "key:$i")" = "value:$i" ] || MISSING=$((MISSING + 1))
done

echo
redis-cli -p "$PORT" SET post-recovery ok >/dev/null
USABLE=$(redis-cli -p "$PORT" GET post-recovery)

if [ "$MISSING" -eq 0 ] && [ "$AFTER" -ge "$BEFORE" ] && [ "$USABLE" = "ok" ]; then
  echo "PASS  $SAMPLED sampled keys all present, $AFTER >= $BEFORE, server writable again"
  exit 0
fi
echo "FAIL  $MISSING of $SAMPLED sampled keys missing"
exit 1
