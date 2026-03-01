# Plan: Mode-5 Rolling Upgrade with RollingSeedCorpus

## Goal
Introduce a dedicated seed corpus for testingMode=5 so rolling-upgrade fuzzing is fully decoupled from full-stop seed/test generation while preserving tri-cluster differential execution:
- old-old
- old-new (rolling)
- new-new

## Problems Addressed
- Mode-5 previously risked borrowing full-stop generation flow (`fuzzOne`/`fullStopCorpus`) to bootstrap command-sequence mutation seeds.
- This created semantic mismatch risk and seed-null failures for test-plan mutation type-4.
- Validation read collection in rolling path could be skipped when oracle was empty, causing incomplete per-lane visibility.

## Design
1. Add `RollingSeed` and `RollingSeedCorpus` (server package).
2. In `FuzzingServer#getOneTest`, mode-5 uses rolling-only flow:
   - `fuzzRollingTestPlan()`
   - if empty, `bootstrapRollingSeedCorpusForMode5()`
   - retry `fuzzRollingTestPlan()`
3. Add rolling-only generation path:
   - `generateAndEnqueueTestPlansFromRollingSeed(...)`
   - `generateTestPlan(RollingSeed)`
4. Keep existing test-plan mutation path for non-empty `testPlanCorpus`.
5. On interesting differential feedback in mode-5, add both:
   - `testPlanCorpus` entry
   - corresponding `RollingSeedCorpus` entry
6. Improve rolling verification visibility:
   - server logs lane validation size (`old-old`, `rolling`, `new-new`)
   - client always executes validation commands and records result size (oracle-empty no longer blocks collection)

## Invariants
- Mode-5 must not invoke mode-3 fallback packet generation.
- Mode-5 must not depend on full-stop corpus mutation state for test-plan generation.
- Rolling tri-cluster execution remains the oracle basis.
- `Seed is null, cannot mutate command sequence` should not occur after bootstrap.

## Verification Plan
1. Local compile: `./gradlew classes -x test`.
2. Deploy changed Java files to all CloudLab nodes.
3. Rebuild classes on each node.
4. Launch 6 distributed jobs in mode-5 with continuous rounds.
5. Verify per-node evidence:
   - bootstrap logs present
   - rolling seed corpus update logs present
   - tri-diff logs present
   - validation lane-size logs present
   - no mode-3 fallback logs
   - no null-seed mutation logs
6. Confirm liveness by time-separated monitor snapshots showing increasing `total_exec`/`diff_feedback_packets`.

## Expected Outcome
Stable rolling-upgrade fuzzing in mode-5 with dedicated rolling seed lifecycle, consistent mutation behavior, and auditable tri-diff/validation evidence across Cassandra/HBase/HDFS job pairs.
