# CloudLab Verification Report: Mode-5 RollingSeedCorpus

- Date: 2026-02-26 01:22:10 CST
- Prefix: `feb26_mode5_rollseed_v2`
- Machines: 6

## Summary

All six CloudLab jobs are running in testingMode=5 with rolling-upgrade tri-cluster execution (old-old, old-new, new-new).
Mode-5 bootstrap and rolling-seed corpus updates are present; no mode-3 fallback and no null-seed mutation error were observed.

## Per-Machine Status

| Machine | Run | total_exec,diff_packets (monitor tail) | bootstrap | rollingSeed updates | validation-size logs | tri-diff logs | mode3 fallback | null-seed error | all3-failed | server_alive | docker_running |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| c220g5-110417.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job1_c220g5-110417.wisc.cloudlab.us | 2,2 | 1 | 2 | 2 | 4 | 0 | 0 | 0 | 1 | 6 |
| c220g5-110402.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job2_c220g5-110402.wisc.cloudlab.us | 3,3 | 1 | 3 | 3 | 6 | 0 | 0 | 0 | 1 | 6 |
| c220g5-111224.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job3_c220g5-111224.wisc.cloudlab.us | 2,2 | 1 | 2 | 2 | 4 | 0 | 0 | 0 | 1 | 9 |
| c220g5-111228.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job4_c220g5-111228.wisc.cloudlab.us | 2,2 | 1 | 2 | 2 | 4 | 0 | 0 | 0 | 1 | 15 |
| c220g5-111230.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job5_c220g5-111230.wisc.cloudlab.us | 4,4 | 1 | 4 | 4 | 8 | 0 | 0 | 0 | 1 | 9 |
| c220g5-111226.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job6_c220g5-111226.wisc.cloudlab.us | 4,4 | 1 | 4 | 4 | 8 | 0 | 0 | 0 | 1 | 9 |

## Observed Fuzz Outcomes (Latest Status Snapshot)

| Machine | Run | total_exec | event_crash | inconsistency | error_log |
|---|---|---:|---:|---:|---:|
| c220g5-110417.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job1_c220g5-110417.wisc.cloudlab.us | 2 | 0 | 0 | 0 |
| c220g5-110402.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job2_c220g5-110402.wisc.cloudlab.us | 3 | 0 | 0 | 0 |
| c220g5-111224.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job3_c220g5-111224.wisc.cloudlab.us | 2 | 2 | 0 | 0 |
| c220g5-111228.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job4_c220g5-111228.wisc.cloudlab.us | 2 | 2 | 0 | 0 |
| c220g5-111230.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job5_c220g5-111230.wisc.cloudlab.us | 4 | 0 | 0 | 0 |
| c220g5-111226.wisc.cloudlab.us | feb26_mode5_rollseed_v2_job6_c220g5-111226.wisc.cloudlab.us | 4 | 0 | 1 | 0 |

## Key Evidence Patterns

Expected patterns found in server logs:
- `Mode 5 bootstrap: generating rolling seed...`
- `Mode 5 bootstrap imported 1 generated seed into rollingSeedCorpus`
- `Validation results collected (old-old=..., rolling=..., new-new=...)`
- `Message identity tri-diff: MessageTriDiff{...}`
- `Mode 5 rollingSeedCorpus updated from interesting test plan, size=...`

Unexpected patterns checked and not observed:
- `execute example test plan` (mode-3 fallback)
- `Seed is null, cannot mutate command sequence`

## Code Paths Verified

- `src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java`: mode-5 uses rolling-only path (`fuzzRollingTestPlan` + `RollingSeedCorpus`)
- `src/main/java/org/zlab/upfuzz/fuzzingengine/server/RollingSeed.java`: rolling seed wrapper
- `src/main/java/org/zlab/upfuzz/fuzzingengine/server/RollingSeedCorpus.java`: rolling seed corpus
- `src/main/java/org/zlab/upfuzz/fuzzingengine/RegularTestPlanThread.java`: validation commands always executed and logged

## Conclusion

Mode-5 rolling-upgrade fuzzing now runs with a dedicated rolling seed corpus and active tri-cluster differential execution on all six CloudLab machines. The run produced non-trivial outcomes (event-crash and inconsistency signals), confirming the mode-5 pipeline is not stuck in a no-op path.
