# Prompt & Code Trail — Worker Heartbeat and Lease Monitor

SourceDoc-style trail pairing each prompt, model, plan, version, and file line range on branch `jax/M4S6/UnitTest`.

- **Feature:** Worker Heartbeat and Lease Monitor (Penpot Scalable Exporter)
- **Model:** Gemini 3.8 Flash (High)
- **Date:** 2026-11-11

---

## Line-Range Map

| Artifact | Lines | Version | Prompt | Purpose |
|---|---|---|---|---|
| `exporter/src/app/jobs/lease.cljs` | `L1–L38` | `v2` | P2 | Namespace setup, ID/status validation helpers, `expired?` boundary predicate |
| `exporter/src/app/jobs/lease.cljs` | `L40–L79` | `v2` | P2 | `assert-active-owner!` guarding missing, mismatched, revoked, completed, and expired leases |
| `exporter/src/app/jobs/lease.cljs` | `L81–L113` | `v2` | P2 | `create-monitor` (clock, TTL, lifecycle callbacks) and `get-lease` |
| `exporter/src/app/jobs/lease.cljs` | `L115–L147` | `v2` | P2 | `acquire!` (active lease creation, duplicate rejection, post-revoke reassignment) |
| `exporter/src/app/jobs/lease.cljs` | `L149–L193` | `v2` → `v2.1` | P2 | `heartbeat!`, `update-status!`, `complete!` (renamed shadowed `next` → `updated` at `L159, L187`) |
| `exporter/src/app/jobs/lease.cljs` | `L195–L219` | `v2` | P2 | `reclaim-expired!` (revokes expired leases, triggers orphan & requeue hooks) |
| `exporter/test/exporter_tests/lease_test.cljs` | `L1–L45` | `v2` → `v2.1` | P2 | Deterministic atom clock & fake event store harness (`setup`, `capture-error-data`) |
| `exporter/test/exporter_tests/lease_test.cljs` | `L47–L67` | `v2` | P2 | Req 1 tests: TTL lease acquisition and per-job isolation |
| `exporter/test/exporter_tests/lease_test.cljs` | `L69–L118` | `v2` | P2 | Req 2 tests: heartbeat renewal, `TTL - 1 ms`, exact `TTL`, status updates, completion |
| `exporter/test/exporter_tests/lease_test.cljs` | `L120–L165` | `v2` | P2 | Req 3 tests: `TTL + 1 ms` orphan flagging & requeue, selective sweep, idempotency, reassignment |
| `exporter/test/exporter_tests/lease_test.cljs` | `L167–L236` | `v2` | P2 | Req 4 tests: expired/revoked/completed/zombie rejection |
| `exporter/test/exporter_tests/lease_test.cljs` | `L238–L312` | `v2` | P2 | Req 5 tests: nil/blank/non-string IDs, duplicates, mismatches, invalid monitor config |
| `exporter/test/exporter_tests/runner.cljs` | `L16, L30` | `v2` | P2 | Registered `exporter-tests.lease-test` in test runner |
| `cs4233-docs/PROJECT_LOG.md` | `L280–L296` | `v5` | P5 | Added Session 24 summary |

---

## Prompt Versions

### v1 — JUnit 5 Specification & Markdown Plan (`09:12`)

- **Model:** Gemini 3.8 Flash (High)
- **Prompt (P1):**
  > Feature Name: Worker Heartbeat and Lease Monitor
  >
  > Feature Description: My feature is worker heartbeat and lease tracking for our Penpot scalable exporter. When a worker claims an export job, it receives a time-limited lease that must be renewed via periodic heartbeats. If a worker crashes or freezes during rendering and misses its deadline, the monitor revokes the lease and returns the job to the queue for reassignment.
  >
  > Requirements:
  > - A worker claiming an export job receives a time-limited execution lease.
  > - Valid heartbeats from the assigned worker extend the lease expiration.
  > - If a lease expires without a heartbeat, the job is flagged ORPHANED and reclaimed.
  > - Status updates or heartbeats from an expired or revoked lease are rejected.
  > - Null, blank, or mismatched worker and job IDs throw validation exceptions.
  >
  > AI Prompt: Generate a complete JUnit 5 test class (`WorkerLeaseMonitorTest`) verifying [1–5] with standard JUnit 5, Mockito (`Clock`), AssertJ, normal paths, boundary timeouts, invalid inputs, `should[Behavior]_when[Condition]` naming, requirement notes, self-contained file, and implementation markdown.
- **Plan:** Inspect Penpot's `exporter/` codebase first; generate a standalone 23-test `WorkerLeaseMonitorTest.java` and requirement-mapped markdown in chat without adding Java files to the ClojureScript repo.
- **Result:** Returned `WorkerLeaseMonitorTest.java` and markdown rationale in chat; noted that Penpot's exporter runs on ClojureScript rather than Java.

### v2 — ClojureScript Exporter Implementation & Unit Tests (`09:17`)

- **Model:** Gemini 3.8 Flash (High)
- **Prompt (P2):**
  > Selected option: `(Recommended) Implement equivalent heartbeat and lease unit tests in Penpot's ClojureScript exporter`
  >
  > now implement this
- **Plan:**
  1. Keep existing Redis-backed `app.jobs`, `app.jobs.queue`, and `app.jobs.worker` untouched so existing CI tests stay green.
  2. Add `app.jobs.lease` (`exporter/src/app/jobs/lease.cljs`) with an injectable clock, positive integer TTL, atom-backed lease state, lifecycle hooks, and `ex/raise` validation/conflict errors.
  3. Add 24 deterministic `cljs.test` cases in `exporter/test/exporter_tests/lease_test.cljs` using an in-memory fake clock and event store (no Redis needed).
  4. Register `exporter-tests.lease-test` in `exporter/test/exporter_tests/runner.cljs`.
- **Files & Lines:**
  - Created `exporter/src/app/jobs/lease.cljs` (`L1–L219`)
  - Created `exporter/test/exporter_tests/lease_test.cljs` (`L1–L312`)
  - Modified `exporter/test/exporter_tests/runner.cljs` (`L16`, `L30`)
- **v2.1 Cleanup (`09:23`):**
  - `exporter/src/app/jobs/lease.cljs` (`L159`, `L187`): renamed `next` to `updated` to avoid shadowing `cljs.core/next`.
  - `exporter/test/exporter_tests/lease_test.cljs` (`L35–L37`): removed unused `:now-ms` and `:set-time!` keys from `setup`.
- **Verification (v2.1):** Python delimiter and whitespace check passed on all three files (`clojure`/`pnpm` not yet verified).

### v3 — Local Toolchain Diagnosis (`09:27`)

- **Model:** Gemini 3.8 Flash (High)
- **Prompt (P3):**
  > (Terminal output: `zsh: command not found: pnpm` running `pnpm install`, `pnpm run lint:clj`, `pnpm run check-fmt:clj`, `pnpm run test:quiet`)
- **Plan:** Check installed Node, Corepack, Homebrew, and Docker paths on the machine and provide exact Homebrew and Docker setup commands.
- **Result:** Identified missing `pnpm`, `clojure`, `cljfmt`, and `clj-kondo` on `PATH` and returned native Homebrew and Docker options.

### v4 — Local Formatting, Linting & Test Execution (`09:33`)

- **Model:** Gemini 3.8 Flash (High)
- **Prompt (P4):**
  > you do it
- **Plan:** Install missing CLI tools via Homebrew (asking/verifying existing Cellar links), install `exporter/` npm deps, run `cljfmt check`, run `clj-kondo`, and compile/run `exporter-tests.lease-test` on Node.
- **Verification Results:**
  - `pnpm install` (v12.5.1): installed 96 packages cleanly.
  - `pnpm run check-fmt:clj`: `All source files formatted correctly`.
  - `clj-kondo` via `clojure -M` (since `brew install borkdude/brew/clj-kondo` failed on missing Xcode CLT): `linting took 825ms, errors: 0, warnings: 0`.
  - `shadow-cljs` `:node-test` (`target/lease-test/test.cjs`) + Node: `Ran 24 tests containing 128 assertions. 0 failures, 0 errors.`
  - Full `pnpm run test:quiet`: blocked locally on missing `exporter/src/app/wasm/shared.js` (`render-wasm/build export` requires Emscripten `/opt/emsdk` / Docker).

### v5 — Prompt & Code Trail (`09:44`)

- **Model:** Gemini 3.8 Flash (High)
- **Prompt (P5):**
  > make a trail of the prompts we use similar to the source docs
- **Plan:** Write a SourceDoc-style trail mapping each prompt, model, plan, version, and file line range to `cs4233-docs/LEASE_PROMPT_TRAIL.md`, and add Session 24 to `cs4233-docs/PROJECT_LOG.md`.
- **Files & Lines:**
  - Created `cs4233-docs/LEASE_PROMPT_TRAIL.md`
  - Updated `cs4233-docs/PROJECT_LOG.md` (`L280–L296`)
