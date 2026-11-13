#!/usr/bin/env bash
# Run each saved AI test through Maven, one at a time.
# Usage (from export-job-dispatcher/):
#   bash experiment/run-all.sh           # runs experiment/run1..3/ExportJobDispatcherTest.java
#   bash experiment/run-all.sh repair1   # runs the repair attempt: experiment/run1..3/repair1/...
# Copies the test into src/test, runs `mvn -B clean test`, saves the output to
# results.txt next to the test, then deletes the copy so no generated test
# stays in src/test.
set -u
TESTDIR=src/test/java/app/penpot/exporter/dispatcher
SUB=${1:-}
for n in 1 2 3; do
  dir="experiment/run$n"
  [ -n "$SUB" ] && dir="$dir/$SUB"
  f="$dir/ExportJobDispatcherTest.java"
  if [ ! -f "$f" ]; then echo "run$n: $f missing, skipped"; continue; fi
  cp "$f" "$TESTDIR/ExportJobDispatcherTest.java"
  echo "=== run$n ${SUB} ==="
  mvn -B clean test 2>&1 | tee "$dir/results.txt" | grep -E "Tests run:|COMPILATION ERROR|BUILD" | tail -3
  rm -f "$TESTDIR/ExportJobDispatcherTest.java"
done
