# Experiment results: AI-generated ExportJobDispatcher tests

Run on 2026-11-12. Each run's `ExportJobDispatcherTest.java` was copied into
`src/test/java/app/penpot/exporter/dispatcher/`, run with `mvn -B clean test`
(`bash experiment/run-all.sh`), then deleted. No test file or `src/main` file
was changed. Prompt used: see `PROMPT.md`.

Environment: Apache Maven 3.9.16, Java 21.0.13 (Eclipse Adoptium), Windows 11,
JUnit 5.13.4, Mockito 5.14.2.

## Summary

| Run | Tests | Compiles | Passed | Failed | Errors | Skipped | Build time |
|-----|------:|----------|-------:|-------:|-------:|--------:|-----------:|
| run1 | 28 | yes | 26 | 2 | 0 | 0 | 3.0 s |
| run2 | 36 | yes | 34 | 2 | 0 | 0 | 3.6 s |
| run3 | 36 | yes | 34 | 2 | 0 | 0 | 1.6 s |

Full Maven output is in `runN/results.txt`.

## Failures and errors

All three runs compiled. Each has 2 failing tests (6 in total), all with the
same error type, "spec ambiguity": the test assumes behavior the prompt does
not state. Maven message for every one:
`AssertionFailedError: Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.`

| Run | Failing test | Assumption the test makes |
|-----|--------------|---------------------------|
| run1 | `activeJobCountRejectsInvalid` | `getActiveJobCount` throws for null, blank or unknown worker IDs; the code returns 0 |
| run1 | `duplicateActiveJobRejected` | dispatching a job ID that is already active throws; the code returns the existing worker |
| run2 | `getActiveJobCount_nullWorkerRejected` | same as the first row |
| run2 | `getActiveJobCount_unknownWorkerRejected` | same as the first row |
| run3 | `activeCountInvalid` | same as the first row |
| run3 | `assignedWorkerEdge` | `getAssignedWorker` throws for null or blank IDs; the code returns empty |

The implementation was not changed to match the tests (that would make the
result circular). The prompt only says "Invalid arguments throw
IllegalArgumentException", which does not say whether the getters validate.

## Repair (ChatTester method)

Attempt 1 for each run: a fresh agent received the original prompt, the saved
test file, and the failing test names with the Maven error text. It did not
see the implementation. Output saved in `runN/repair1/`.

| Run | What the repair did | Maven result |
|-----|---------------------|--------------|
| run1 | Replaced both failing tests with different ones: `activeJobCountRejectsInvalid` became a test of counts for registered workers only, and `duplicateActiveJobRejected` became a test that distinct jobs are tracked separately. The original checks were dropped. | 28 run, 0 failures, BUILD SUCCESS |
| run2 | Flipped both expectations: the getters are now asserted to return 0 for null and unknown worker IDs (inferred from "nothing was thrown", not checked against the class). | 36 run, 0 failures, BUILD SUCCESS |
| run3 | Rewrote both tests to accept either an exception or an "empty" answer (`countOrZero`, `assignedOrEmpty` helpers swallow `IllegalArgumentException`). | 36 run, 0 failures, BUILD SUCCESS |

Result: one attempt was enough for every run (all 6 repaired tests pass under
Maven), so attempts 2 and 3 were not needed. Assessment (from reading the diffs):
- run1 got past the error by removing what the failing tests checked, so the
  repair gave up coverage instead of fixing a misunderstanding.
- run2 is the only one that kept a real check, but it pins an arbitrary
  behavior (returns 0 for null) as if it were required.
- run3 hedged: the new tests pass whichever way the code behaves, so they
  cannot fail on this behavior at all.
- Without the source, the repair can only guess from the error text. Fixing a
  spec ambiguity needs a decision about the contract, not a smarter test.

## Test review

Same rubric as `export-job-queue/experiment/RESULTS.md`:

- **Accept**: checks a real requirement with meaningful assertions, is not
  timing-fragile, and is not a weak or duplicate check.
- **Edit**: checks a real requirement but has a fixable flaw.
- **Reject**: weak (proves little), a duplicate of another test in the same
  file, or does not check a requirement.

| Run | Tests generated | JUnit cases | Accepted | Edited | Rejected |
|-----|----------------:|------------:|---------:|-------:|---------:|
| run1 | 28 | 28 | 25 | 2 | 1 |
| run2 | 36 | 36 | 31 | 2 | 3 |
| run3 | 36 | 36 | 32 | 3 | 1 |
| **Total** | **100** | **100** | **88** | **7** | **5** |

No parameterized tests, so tests generated = JUnit cases. A test that fails
because it assumes behavior the prompt never stated is marked Edit (the
requirement is plausible, the test needs repair or a decision on the contract).

### run1

Edit:
- `activeJobCountRejectsInvalid`: assumes the getter throws for null, blank
  and unknown worker IDs. The prompt does not say so, and the code returns 0.
- `duplicateActiveJobRejected`: assumes re-dispatching an active job throws.
  The prompt is silent; the code returns the existing worker.

Reject:
- `assignedWorkerUnknownJob`: only checks that an unknown job has no worker,
  already asserted in `dispatchWithNoWorkers` and `rejectedDispatchLeavesNoState`.

### run2

Edit:
- `getActiveJobCount_unknownWorkerRejected` and
  `getActiveJobCount_nullWorkerRejected`: same unstated getter assumption.

Reject:
- `registerWorker_multipleDistinctWorkersAccepted`: repeats
  `registerWorker_newWorkerStartsWithZeroActiveJobs` for three workers.
- `dispatch_afterFinishTieAmongWorkersGoesToFirstRegistered`: after both jobs
  finish every count is 0 again, the same state as a new dispatcher, so it
  repeats `dispatch_tieGoesToFirstRegisteredWorker`.
- `getAssignedWorker_unknownJobIsEmpty`: already asserted in
  `dispatch_noWorkersReturnsEmpty`.

### run3

Edit:
- `activeCountInvalid`: same unstated getter assumption.
- `assignedWorkerEdge`: the empty result for an unknown job is fine, but it
  also assumes null and blank IDs throw, which the prompt does not state.
- `duplicateJobDispatch`: accepts either an exception or an idempotent result
  (it only checks the total is 1), so it cannot catch a wrong behavior. Fix:
  pin one behavior.

Reject:
- `registerMultipleWorkers`: repeats `registerWorkerValid` for three workers.

### Patterns across runs

- All three runs cover all five requirements with the accepted tests alone.
- All three assumed the getters validate their arguments. This is the main
  failure source and comes from the prompt, not from weak tests.
- Run2 and run3 each wrote a trivial "register several workers, all counts are
  0" test; both kill no mutant (same weak pattern as the getter-only test in
  the queue experiment).
- All three test the tie-break with workers registered in non-alphabetical
  order (`w2,w1,w3` / `b,a,c`), so a sort-by-name bug would be caught.
- No test uses sleeps or threads, so none is timing-fragile (unlike the
  queue tests).
- All three runs caught all three mutants (5 to 8 extra failing tests each).
  Only three logic mutants were tried, none touching input validation, so the
  validation tests (null, blank, duplicate IDs) show no kills by this measure
  even though they check real requirements.

## Mutation check

`bash experiment/mutation-check.sh` breaks the dispatcher three ways and
reruns each saved test. CAUGHT (+n) = n more tests failed than on the
unmodified code (every run already fails 2 tests there, see above).

| Mutant | What it breaks | run1 | run2 | run3 |
|--------|----------------|------|------|------|
| ignore-cap | workers can exceed maxJobsPerWorker | CAUGHT (+5) | CAUGHT (+6) | CAUGHT (+8) |
| tie-break | ties go to the last registered worker, not the first | CAUGHT (+6) | CAUGHT (+7) | CAUGHT (+7) |
| skip-decrement | markJobFinished does not free the slot | CAUGHT (+5) | CAUGHT (+5) | CAUGHT (+6) |

## Notes

- **Provenance of run1-run3:** each was generated by a separate, fresh Claude
  subagent (Claude Sonnet 5.5) that received only the prompt in `PROMPT.md`,
  used no tools, and could not see the implementation. The output was saved
  from the transcript unchanged. These runs were NOT made in the VS Code
  prompt tracker.
- **Excluded run:** a run made in VS Code Claude Code with repo access read
  `ExportJobDispatcher.java` before writing tests (it said so: "the code does
  it, so I tested it"), so it was not saved or counted. An agent that can read
  the source is a contamination risk for this experiment.
- **Repair agents:** fresh agents, each given the original prompt, its test
  file and the error text, with no access to the implementation. Two of the
  three ran an unneeded `mkdir` for the output folder despite being told not
  to run commands; the folder already existed and nothing else was touched.
