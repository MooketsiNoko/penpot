#!/usr/bin/env bash
# Break the dispatcher on purpose and see which AI test runs notice.
# Usage (from export-job-dispatcher/):  bash experiment/mutation-check.sh
#
# A mutant is CAUGHT when a run has MORE failing tests than it has on the
# unmodified code. (Some runs already fail tests on the original code, so a
# plain pass/fail check would call every mutant caught.)
set -u
SRC=src/main/java/app/penpot/exporter/dispatcher/ExportJobDispatcher.java
TESTDIR=src/test/java/app/penpot/exporter/dispatcher
TEST_CMD=${TEST_CMD:-mvn -B clean test}

MUTANT_NAMES=(ignore-cap tie-break skip-decrement)
MUTANT_SED=('s/load < maxJobsPerWorker && load < bestLoad/load < bestLoad/' 's/load < bestLoad/load <= bestLoad/' '/merge(worker, -1/d')

if [ "${1:-}" = "--list" ]; then printf '%s\n' "${MUTANT_NAMES[@]}"; exit 0; fi

# Number of failing tests (failures + errors); ERR when no Maven summary was
# printed (for example the code did not compile).
count_failures() {
  local out line
  out=$($TEST_CMD 2>&1 | tr -d '\r')
  line=$(printf '%s\n' "$out" | grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' | tail -1)
  if [ -z "$line" ]; then echo ERR; return; fi
  echo $(( $(printf '%s' "$line" | sed -E 's/.*Failures: ([0-9]+), Errors: ([0-9]+).*/\1+\2/') ))
}

cp "$SRC" "$SRC.orig"
cleanup() { cp "$SRC.orig" "$SRC"; rm -f "$SRC.orig" "$TESTDIR/ExportJobDispatcherTest.java"; }
trap cleanup EXIT

BASE=()
row=""
for n in 1 2 3; do
  f="experiment/run$n/ExportJobDispatcherTest.java"
  if [ ! -f "$f" ]; then BASE[$n]=missing; row="$row $(printf '%-14s' missing)"; continue; fi
  cp "$SRC.orig" "$SRC"
  cp "$f" "$TESTDIR/ExportJobDispatcherTest.java"
  BASE[$n]=$(count_failures)
  row="$row $(printf '%-14s' "${BASE[$n]}")"
done
printf '%-16s %-14s %-14s %-14s\n' mutant run1 run2 run3
printf '%-16s%s\n' "(baseline fails)" "$row"

for i in "${!MUTANT_NAMES[@]}"; do
  row=""
  for n in 1 2 3; do
    f="experiment/run$n/ExportJobDispatcherTest.java"
    if [ ! -f "$f" ]; then row="$row $(printf '%-14s' missing)"; continue; fi
    cp "$SRC.orig" "$SRC"
    sed -i.bak "${MUTANT_SED[$i]}" "$SRC" && rm -f "$SRC.bak"
    if cmp -s "$SRC" "$SRC.orig"; then row="$row $(printf '%-14s' NO-CHANGE)"; continue; fi
    cp "$f" "$TESTDIR/ExportJobDispatcherTest.java"
    c=$(count_failures)
    if [ "$c" = ERR ] || [ "${BASE[$n]}" = ERR ]; then r=ERROR
    elif [ "$c" -gt "${BASE[$n]}" ]; then r="CAUGHT(+$((c - BASE[$n])))"
    else r=SURVIVED; fi
    row="$row $(printf '%-14s' "$r")"
  done
  printf '%-16s%s\n' "${MUTANT_NAMES[$i]}" "$row"
done
