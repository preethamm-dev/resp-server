#!/usr/bin/env python3
"""Summarises benchmark runs into median-with-spread tables.

Reports the median across runs rather than the best. Quoting a best-of-N is how
benchmarks flatter themselves: it reports the luckiest scheduling the machine
happened to produce, not what the server does. The spread is printed alongside so a
reader can see how noisy the measurement was and discount accordingly -- a 5% gap
between two subjects means nothing if either varies by 15% between its own runs.
"""

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

RESULTS = Path("benchmarks/results/results.csv")
SUBJECT_ORDER = ["virtual", "eventloop", "redis"]
SUBJECT_LABEL = {
    "virtual": "resp-server (virtual threads)",
    "eventloop": "resp-server (event loop)",
    "redis": "Redis",
}


def load(path):
    """rows[(config, test, subject)] -> list of {metric: value} per run."""
    rows = defaultdict(list)
    with path.open() as handle:
        for row in csv.DictReader(handle):
            try:
                rows[(row["config"], row["test"], row["subject"])].append({
                    "rps": float(row["rps"]),
                    "p50": float(row["p50_ms"]),
                    "p95": float(row["p95_ms"]),
                    "p99": float(row["p99_ms"]),
                })
            except (ValueError, KeyError):
                continue
    return rows


def spread(values):
    """Percentage spread between the extremes, relative to the median."""
    if len(values) < 2:
        return 0.0
    median = statistics.median(values)
    if median == 0:
        return 0.0
    return (max(values) - min(values)) / median * 100


def main():
    if not RESULTS.exists():
        print(f"no results at {RESULTS} -- run benchmarks/run-benchmarks.sh first",
              file=sys.stderr)
        return 1

    rows = load(RESULTS)
    configs = sorted({key[0] for key in rows})

    for config in configs:
        tests = sorted({key[1] for key in rows if key[0] == config})
        print(f"\n## {config}\n")
        print("| test | subject | ops/sec (median) | spread | p50 ms | p95 ms | p99 ms |")
        print("|---|---|---:|---:|---:|---:|---:|")

        for test in tests:
            for subject in SUBJECT_ORDER:
                runs = rows.get((config, test, subject))
                if not runs:
                    continue
                rps = [r["rps"] for r in runs]
                print("| {} | {} | {:,.0f} | ±{:.1f}% | {:.3f} | {:.3f} | {:.3f} |".format(
                    test,
                    SUBJECT_LABEL[subject],
                    statistics.median(rps),
                    spread(rps),
                    statistics.median([r["p50"] for r in runs]),
                    statistics.median([r["p95"] for r in runs]),
                    statistics.median([r["p99"] for r in runs]),
                ))

    # Relative throughput, since "how close to Redis" is the question a reader has.
    print("\n## resp-server throughput relative to Redis\n")
    print("| config | test | virtual threads | event loop |")
    print("|---|---|---:|---:|")
    for config in configs:
        for test in sorted({key[1] for key in rows if key[0] == config}):
            redis_runs = rows.get((config, test, "redis"))
            if not redis_runs:
                continue
            baseline = statistics.median([r["rps"] for r in redis_runs])
            if baseline == 0:
                continue
            cells = []
            for subject in ("virtual", "eventloop"):
                runs = rows.get((config, test, subject))
                cells.append(
                    "{:.0f}%".format(statistics.median([r["rps"] for r in runs]) / baseline * 100)
                    if runs else "-")
            print(f"| {config} | {test} | {cells[0]} | {cells[1]} |")

    return 0


if __name__ == "__main__":
    sys.exit(main())
