# Plan: Fix Coupled Differential Lane Timeouts (Rolling vs OnlyNew)

## Problem
Current differential feedback wait is sequential with one shared deadline:
- wait `OnlyOld`
- then `Rolling`
- then `OnlyNew`

If `Rolling` consumes remaining budget and times out, `OnlyNew` is often marked timeout immediately (`remainingMillis <= 0`) even if its own execution might have completed. This inflates/synchronizes `old-new` and `new-new` failure counts.

## Goal
Make lane timeout accounting independent and fair:
- each lane gets its own timeout budget measured from a common start,
- lane failure stats reflect actual lane behavior, not ordering artifact.

## Scope
Primary files:
- `src/main/java/org/zlab/upfuzz/fuzzingengine/FuzzingClient.java`
- `src/main/java/org/zlab/upfuzz/fuzzingengine/packet/TestPlanFeedbackPacket.java`
- `src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java`

## Design Changes
1. Independent lane deadlines
- At differential start, record `packetStartMs`.
- Define `laneTimeoutMs` once.
- For each lane, compute `laneDeadlineMs = packetStartMs + laneTimeoutMs` (same configured timeout, independent accounting).
- Do not derive lane timeout from previous lane wait consumption.

2. Concurrent collection (remove sequential bias)
- Replace sequential `future.get(remainingMillis)` calls with one of:
  - `ExecutorCompletionService` loop, or
  - `CompletableFuture` with per-lane timeout handling.
- Track each lane result independently until all lanes resolved (success/timeout/exception/null).

3. Explicit lane outcome fields
- Extend `TestPlanFeedbackPacket` with structured status, e.g.:
  - `laneStatus` enum: `OK`, `TIMEOUT`, `EXCEPTION`, `NULL_PACKET`, `INTERRUPTED`
  - `laneFailureReason` string
- Keep existing `isEventFailed` semantics unchanged for backward compatibility: it represents execution failure inside that lane's test run (for example, event execution crash, coverage collection failure), not lane collection/wait outcome.
- Define semantic contract explicitly:
  - `laneStatus=OK` means feedback packet collection succeeded for that lane.
  - `laneStatus=OK` does **not** imply execution success; `isEventFailed` may still be `true`.
  - Non-`OK` `laneStatus` (`TIMEOUT`, `EXCEPTION`, `NULL_PACKET`, `INTERRUPTED`) represents collection/wait failures and must not overwrite or redefine execution-failure semantics.

4. Server-side counting/reporting
- In differential `updateStatus(...)`, log per-lane status explicitly.
- Add counters (or parseable logs) for:
  - `old-old lane timeout count`
  - `old-new lane timeout count`
  - `new-new lane timeout count`
- Keep existing high-level counters unchanged for compatibility.

5. Failure report clarity
- In generated reports, include lane + reason with normalized tags:
  - `[OnlyOld][TIMEOUT] ...`
  - `[Rolling][TIMEOUT] ...`
  - `[OnlyNew][TIMEOUT] ...`
- This allows exact per-lane failure/pass counting without ambiguity.

## Implementation Steps
1. Refactor client wait flow
- Introduce `LaneResult` helper to store lane packet + status.
- Collect 3 futures concurrently; apply per-lane timeout checks against independent deadlines.

2. Update fallback builder
- Ensure fallback packet stores lane status/reason in dedicated fields.

3. Update server processing
- Read lane status fields and log/counter them directly.
- Preserve current behavior for bug report generation.

4. Add tests
- Unit/integration-style checks for:
  - Rolling timeout does not force OnlyNew timeout automatically.
  - OnlyNew can succeed when Rolling times out.
  - Per-lane timeout counts diverge correctly when behavior differs.

5. Validate on CloudLab
- Relaunch 6 jobs in mode 5.
- Verify for HBase jobs that Rolling timeout count can differ from OnlyNew timeout count.
- Recompute per-lane exec/fail/pass from logs.

## Acceptance Criteria
- `old-new` and `new-new` failure counts are no longer mechanically identical due to ordering.
- Per-lane timeout logs show independent outcomes.
- Existing fuzzing workflow remains stable (no regression in packet dispatch/processing).
