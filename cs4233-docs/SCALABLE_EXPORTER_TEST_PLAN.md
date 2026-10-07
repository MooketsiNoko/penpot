# Scalable Exporter Integration-Test Plan

## Scope

Replace the always-successful `test-scalable-exporter` placeholder in
`.github/workflows/ci-cd-assignment.yml` with integration tests for the
scalable exporter already on `develop`. Do not change the normal
single-process exporter (`PENPOT_EXPORTER_ROLE=all`).

## Current CI placeholder

The placeholder is the `test-scalable-exporter` job in
`.github/workflows/ci-cd-assignment.yml` (around lines 105--136). It only
prints a `STUB` message and exits successfully. It does not check out code,
start Valkey, build the exporter, or make an assertion. The comments say that
the queue and worker pool do not exist, but that is no longer true on this
branch.

The separate `test-exporter` job runs `exporter/scripts/test`, but it does not
start Valkey or set `PENPOT_REDIS_URI`. Consequently, the existing
`exporter-tests.queue-test` tests report themselves as skipped in that job.

## Existing scalable-exporter flow

The current implementation already contains these parts:

- An exporter runs in `all`, `api`, or `worker` mode. `all` keeps the original
  one-process behavior; `api` serves export requests and queues them; `worker`
  claims and renders queued jobs.
- `app.handlers.export` sends both existing export paths through the shared
  queue in distributed mode.
- `app.jobs.queue` stores payloads and job IDs in Valkey. `BLMOVE` atomically
  moves a claimed job to that worker's processing list.
- `app.instance` records exporter instances and refreshes their heartbeats.
  `app.jobs.worker` reaps the processing list of an instance whose heartbeat
  has expired and requeues its work.
- The job record stores the current owner and increments `:attempts` when a
  worker adopts it. `app.jobs/requeue!` changes a recovered job back to
  `queued` only while attempts remain below
  `PENPOT_EXPORTER_MAX_ATTEMPTS` (default: 3); otherwise it ends in `error`.
- Existing real-Valkey coverage in
  `exporter/test/exporter_tests/queue_test.cljs` covers queue claims, payload
  encryption, acknowledgement, reaping, and the retry limit. It does not run
  two exporter processes or render a successful export.
- `docker/images/docker-compose.yaml` already describes one `api` exporter,
  two `worker` replicas, and Valkey for the project's compose deployment.

The full flow also involves the frontend that the exporter renders, the
backend upload endpoint used for temporary export files, a browser/WASM render
path, and the existing job/resource APIs. Those services are required only if
the integration test performs a real rendered export rather than a controlled
worker-job fixture.

## Gaps and dependencies

The queue implementation exists, but the following test support does not:

- The CI workflow has no Valkey service or `PENPOT_REDIS_URI` for exporter
  tests.
- There is no integration-test runner that starts an `api` exporter and two
  independent `worker` exporter processes with separate instance IDs.
- There is no existing test control that can hold a claimed job, record each
  worker execution, or fail/kill a worker at a known point. The persisted job
  record keeps only its latest owner and attempt count, so it is not an audit
  trail for duplicate executions.
- A real successful export also needs a repeatable frontend/backend/auth and
  upload setup. The current exporter unit-test setup does not provide it.

Issue #6 therefore depends on adding the test harness and observability above
before the requested process-level checks can be implemented. It has no
identified dependency on another numbered repository issue. The stale CI
comment should be corrected when this issue replaces the stub.

## Proposed tests

All tests should use a real isolated Valkey database or key prefix and clean it
up after every run. They should submit jobs through the `api` path, not by
writing queue keys directly, so the test covers job creation and dispatch.

### 1. Two-worker concurrent processing

1. Start Valkey, one exporter in `api` mode, and at least two exporter
   processes in `worker` mode.
2. Submit more independent jobs than one worker can hold at once.
3. Use a controlled test job or a fully provisioned small export fixture that
   records the job ID and worker instance when processing starts and finishes.
4. Wait until every submitted job reaches the terminal `ended` state.

Verify that the completion set equals the submitted job-ID set, each job has
one successful execution record, and the records name at least two worker
instances. Also verify that the queue and every worker processing list are
empty after settlement. Set comparison proves that no job was dropped; one
successful execution record per ID proves that no job was duplicated.

### 2. Recovery after a worker failure

1. Start the same API, Valkey, and two-worker setup with a job that pauses
   after it is claimed.
2. Submit one job and wait for worker A to claim it.
3. Terminate worker A without letting it acknowledge the job.
4. Wait longer than `PENPOT_EXPORTER_HEARTBEAT_TTL`, then allow worker B to
   reap and process the job.

Verify the final record is `ended`, its attempt count is two, the execution
record shows one interrupted claim followed by one successful execution, and
all queue/processing entries are removed. This checks retry after a lost
worker without treating the required retry as a duplicate successful export.

### 3. Bounded retry for repeatedly lost work

1. Configure a small explicit value for `PENPOT_EXPORTER_MAX_ATTEMPTS` (for
   example, 2) and the same controlled pause point.
2. For each attempt, wait for a worker to claim the job, then terminate that
   worker before acknowledgement. Start a replacement worker as needed.
3. Let the final reap occur after the configured maximum is reached.

Verify that the job becomes `error`, that `:attempts` equals the configured
maximum, that no job ID remains queued or in a processing list, and that the
execution log contains no later attempt after a bounded wait. The test must
use explicit deadlines so a stuck retry loop fails CI rather than hanging it.

## CI verification

The replacement `test-scalable-exporter` job should:

1. Start a Valkey service with a health check and pass its URI plus short,
   explicit heartbeat and timeout settings to the test runner.
2. Install exporter dependencies and build the exporter runtime used by the
   spawned API and worker processes.
3. Run the scalable integration suite with a global timeout. Fail on any
   assertion, child-process exit that was not an intentional simulated crash,
   timeout, leftover queue/processing key, or unexpected retry.
4. Always upload the integration-runner log and exporter process logs. These
   logs must include instance IDs, job IDs, state transitions, and attempts so
   a failed concurrency check is diagnosable.

The existing `test-exporter` job may also receive the Valkey service and URI
so that `queue_test.cljs` stops skipping its real-Valkey tests. That is useful
regression coverage, but it does not replace the two-process tests above.

## Files expected to change when implementing the plan

- `.github/workflows/ci-cd-assignment.yml` — replace the placeholder and add
  the Valkey-backed integration-test steps.
- `exporter/test/exporter_tests/queue_test.cljs` — retain or extend the
  existing single-process real-Valkey coverage where appropriate.
- `exporter/test/exporter_tests/runner.cljs` — register any new CLJS test
  namespaces.
- A new exporter integration-test runner and its fixtures under `exporter/test/`
  — start and stop the API/workers, submit jobs, and collect execution data.
- `exporter/package.json` and/or `exporter/scripts/` — add a named command for
  the integration runner.
- Exporter job/worker code only if needed to add a test-only controlled job or
  execution audit. Do not add production behavior solely to make a test pass
  until the smallest safe test interface has been chosen.

`docker/images/docker-compose.yaml` is a reference for the desired topology;
it should not need a production change merely to run the CI test.
