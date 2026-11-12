# Experiment results: AI-generated ExportJobQueue tests

Run on 2026-11-12. Each run's `ExportJobQueueTest.java` was copied into
`src/test/java/app/penpot/exporter/queue/`, run with `mvn -B clean test`,
then deleted. No test file or `src/main` file was changed.

Environment: Apache Maven 3.9.9 (standalone download), Java 21.0.8,
JUnit 5.13.4, Mockito 5.14.2.

## Summary

| Run | Tests | Compiles | Passed | Failed | Errors | Skipped | Build time |
|-----|------:|----------|-------:|-------:|-------:|--------:|-----------:|
| run1 | 60 | yes | 60 | 0 | 0 | 0 | 5.6 s |
| run2 | 51 | yes | 51 | 0 | 0 | 0 | 7.7 s |
| run3 | 53 | yes | 53 | 0 | 0 | 0 | 6.4 s |

Full Maven output is in `runN/results.txt`.

## Failures and errors

None. All three test files compiled and every test passed on the first
run, so no rerun was needed and there are no `results-rerun.txt` files.

## Test review

Each test method was marked Accept, Edit or Reject:

- **Accept**: checks a real requirement with meaningful assertions, is not
  timing-fragile, and is not a weak or duplicate check.
- **Edit**: checks a real requirement but has a fixable flaw, such as a
  sleep-based check or a loose assertion.
- **Reject**: weak (proves little), a duplicate of another test in the
  same file, or does not check a requirement.

A parameterized test counts as one test here. JUnit counts each of its
cases, so the "JUnit cases" column matches the summary table above.

| Run | Tests generated | JUnit cases | Accepted | Edited | Rejected |
|-----|----------------:|------------:|---------:|-------:|---------:|
| run1 | 53 | 60 | 45 | 1 | 7 |
| run2 | 51 | 51 | 44 | 3 | 4 |
| run3 | 46 | 53 | 38 | 2 | 6 |
| **Total** | **150** | **164** | **127** | **6** | **17** |

### run1

Edit:
- `neverRunsMoreThanMaxConcurrent`: sleeps 200 ms hoping to catch a third
  job, and checks peak `<= 2` where it should check `== 2`.

Reject:
- `storesSettings`: only checks getters, not queue behavior.
- `singleSlotRunsJobsOneAtATime`: repeats `neverRunsMoreThanMaxConcurrent`
  with a limit of 1, and relies on 20 ms sleeps to make jobs overlap.
- `checkedAndUncheckedErrorsAreBothRetried`: repeats
  `lastErrorHoldsErrorFromFinalAttempt`, which already retries a checked
  then an unchecked error.
- `fastRenderUnderTimeoutSucceeds`: repeats
  `successfulJobStoresResultAndCallsRendererOnce`; the mock returns at
  once, so the timeout plays no part.
- `cancelRejectsIdThatLooksLikeKnownIdWithWhitespace`: an ID with a
  leading space is just an unknown ID, already covered by
  `cancelRejectsUnknownId`.
- `closeOnEmptyQueueIsSafe`: only checks that nothing throws and the
  counts are 0.
- `submitAfterCloseStillRejectsNullWithSomeException`: accepts any
  `RuntimeException`, so it cannot catch a wrong exception type.

### run2

Edit:
- `neverRunsMoreThanMaxConcurrent`: waits 200 ms (`after(200)`) hoping to
  catch a fourth render, and checks peak `<= 3` where it should check
  `== 3`.
- `timeoutAppliesPerAttemptNotPerJob`: a 600 ms render under a 1 s
  timeout leaves little room on a slow machine; widen the gap.
- `cancelledJobIsStillFindable`: ignores what `cancel()` returns and never
  checks the CANCELLED status.

Reject:
- `storesConfiguration`: only checks getters, not queue behavior.
- `newQueueIsEmpty`: only checks that a new queue has counts of 0.
- `fastRenderWithinTimeoutSucceeds`: repeats `successfulJobStoresResult`;
  a 20 ms render under a 2 s timeout tests nothing new.
- `closeOnEmptyQueueSucceeds`: only checks that nothing throws and the
  counts are 0.

### run3

Edit:
- `respectsConcurrencyLimit`: the 300 ms pause hoping to catch a third
  job adds nothing, since the peak counter already proves the limit.
- `timeoutIsPerAttempt`: a 200 ms render under a 500 ms timeout leaves
  little room on a slow machine; widen the gap.

Reject:
- `storesConfiguration`: only checks getters, not queue behavior.
- `succeedsOnLastAttempt`: the same scenario as `failThenSucceed` with one
  fewer retry.
- `failedJobIsFinal`: repeats `alwaysFailingJobIsRetriedThenFailed`
  (FAILED, attempt count, no extra calls).
- `finishesWithinTimeout`: repeats `successStoresResult`; a 50 ms render
  under a 2 s timeout tests nothing new.
- `submitNullAfterClose`: accepts either of two exceptions, so it cannot
  catch a wrong exception type.
- `closeEmptyQueue`: only checks that nothing throws and the counts are 0.

### Patterns across runs

- All three runs wrote the same weak tests: a getter-only "stores
  settings" test, a "close an empty queue" test, and a "fast render under
  the timeout" test that repeats the basic success case.
- All three checked the concurrency limit with a sleep meant to catch an
  extra job starting. Only run3 also checked that the peak was exactly
  the limit.
- run1 and run3 each wrote a test that accepts more than one exception
  type for `submit(null)` after `close()`.
- In every run, the accepted tests alone still cover all five
  requirements.

## Notes

- Each run printed a JVM warning that Mockito loaded a Java agent at run
  time. This is a Java 21 warning, not a test failure.
- Maven was not installed. `brew install maven` began building 43
  packages from source, including OpenJDK 27, which would have taken
  hours and run the tests on Java 27 instead of the project's Java 21.
  It was stopped, and the standalone Maven 3.9.9 was used instead.
