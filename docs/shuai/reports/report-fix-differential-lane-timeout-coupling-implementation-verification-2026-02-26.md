# Report: Differential Lane Timeout Decoupling Implementation + 6-Node CloudLab Verification

Date: 2026-02-26
Plan: `docs/shuai/plans/plan-fix-differential-lane-timeout-coupling-2026-02-26.md`

## 1. Implementation Applied

### 1.1 Lane-level status model
- Updated `src/main/java/org/zlab/upfuzz/fuzzingengine/packet/TestPlanFeedbackPacket.java`.
- Added:
  - `LaneStatus { OK, TIMEOUT, EXCEPTION, NULL_PACKET, INTERRUPTED }`
  - `laneName`
  - `laneStatus`
  - `laneFailureReason`
- Kept `isEventFailed` for execution-level failures only.

### 1.2 Independent lane timeout collection
- Updated `src/main/java/org/zlab/upfuzz/fuzzingengine/FuzzingClient.java`.
- Differential path now collects three lanes concurrently with independent deadlines from common start time:
  - `collectDifferentialFeedbackPackets(...)`
  - `collectFinishedLaneFeedback(...)`
  - `buildFallbackDiffFeedback(...)`
- Removed sequential shared-deadline coupling behavior.

### 1.3 Server-side per-lane outcome accounting
- Updated `src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java`.
- Added counters:
  - timeout: `oldOldLaneTimeoutNum`, `rollingLaneTimeoutNum`, `newNewLaneTimeoutNum`
  - collection failure: `oldOldLaneCollectionFailureNum`, `rollingLaneCollectionFailureNum`, `newNewLaneCollectionFailureNum`
- Added structured lane outcome logs and summaries:
  - `Differential lane outcome [...]`
  - `Differential lane counters: timeout(...), collectionFailure(...)`
- Added failure artifact dir:
  - `failure/failure_*/lane_collection_failure/`

### 1.4 Unit tests
- Added `src/test/java/org/zlab/upfuzz/fuzzingengine/FuzzingClientDifferentialLaneWaitTest.java`:
  - `rollingTimeoutShouldNotForceOnlyNewTimeout`
  - `laneExecutionExceptionShouldBeClassifiedWithoutEventFailureBit`

## 2. Build/Test Validation (local)
- `./gradlew classes -x test` passed.
- `./gradlew test --tests 'org.zlab.upfuzz.fuzzingengine.FuzzingClientDifferentialLaneWaitTest'` passed.

## 3. CloudLab Verification Procedure (all 6 nodes)

### 3.1 Cleanup before verification
On each node:
- kill tmux sessions matching `upfuzz_*`
- kill server/client java processes
- remove old upfuzz docker containers/networks
- remove old results:
  - `scripts/cloudlab-runner/results/*`
  - `scripts/runner/results/*`
  - `failure/failure_*`

### 3.2 Relaunch command
- Ran distributed launcher with mode 5 and fresh run prefix:
- `scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --distribute --testing-mode 5 --skip-build --skip-pull --run-prefix feb26_lane_timeout_fix_v1`

## 4. Verification Evidence (decoupling correctness)

### 4.1 HBase 2.5.13 -> 2.6.4 (job3, c220g5-111224)
- Latest run dir: `scripts/runner/results/feb26_lane_timeout_fix_v1_job3_c220g5-111224.wisc.cloudlab.us`
- Server summary shows:
  - `lane timeout old/roll/new : 0/1/0`
  - `lane collect fail old/roll/new : 0/1/0`
- This proves rolling timeout does not force new-new timeout.

### 4.2 HBase 2.6.4 -> 3.0.0-beta-1 (job4, c220g5-111228)
- Latest run dir: `scripts/runner/results/feb26_lane_timeout_fix_v1_job4_c220g5-111228.wisc.cloudlab.us`
- Server summary shows:
  - `lane timeout old/roll/new : 0/1/0`
  - `lane collect fail old/roll/new : 0/1/0`
- Same decoupled behavior confirmed.

### 4.3 Other 4 jobs
- job1/job2/job5/job6 show lane timeout counters as `0/0/0` in latest snapshot.

## 5. Per-machine snapshot
- c220g5-110417 (job1): tmux alive; lane timeout `0/0/0`
- c220g5-110402 (job2): tmux alive; lane timeout `0/0/0`
- c220g5-111224 (job3): tmux alive; lane timeout `0/1/0` (rolling only)
- c220g5-111228 (job4): tmux alive; lane timeout `0/1/0` (rolling only)
- c220g5-111230 (job5): tmux alive; lane timeout `0/0/0`
- c220g5-111226 (job6): tmux alive; lane timeout `0/0/0`

## 6. Conclusion
- The timeout-coupling bug is fixed.
- Lane outcomes are now independently classified and counted.
- Rolling timeout no longer implies OnlyNew timeout.
- Verification completed on all 6 CloudLab jobs after cleanup and relaunch.
