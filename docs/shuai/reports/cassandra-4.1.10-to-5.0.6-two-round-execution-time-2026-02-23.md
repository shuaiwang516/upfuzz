# Cassandra Rolling-Upgrade Demo Timing Report (2 Rounds)

## Scope
- Target: Cassandra rolling-upgrade fuzzing demo (`apache-cassandra-4.1.10 -> apache-cassandra-5.0.6`)
- Host: `swang516@c220g5-110417.wisc.cloudlab.us`
- Requirement: measure execution time for 2 diff packets, each packet containing 3 executions:
  - old-old
  - old-new (rolling)
  - new-new

## Clean-Start Verification
Run artifacts directory:
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_timing_2rounds_cleanstart`

Evidence:
- Pre-run cleanup log: `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_timing_2rounds_cleanstart/pre_clean.log`
  - Contains `No upfuzz containers found.`
- Docker pre-check snapshot: `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_timing_2rounds_cleanstart/docker_ps_before_cleanup.txt`
  - Empty container list (`NAMES IMAGE STATUS` only)
- Post-run cleanup log: `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_timing_2rounds_cleanstart/post_clean.log`
  - Cleanup executed and containers removed.

## Run Summary
Source:
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_timing_2rounds_cleanstart/summary.txt`

Key values:
- `target_rounds: 2`
- `observed_rounds: 2`
- `diff_feedback_packets: 2`
- `stop_reason: target_rounds_reached`
- `duration_sec: 579`

## Measurement Method
Primary log:
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_timing_2rounds_cleanstart/upfuzz_client_1.log`

Per execution (one thread in one packet), execution time is:
- Start timestamp: first `Executor.java:execute:215` line whose next line is `handle [Command] Execute {CONSISTENCY ALL;}`
- End timestamp: `RegularTestPlanThread.java:call:344: [HKLOG] error log checking` for the same thread
- Duration (seconds): `end - start`

Thread-to-execution mapping:
- old-new (rolling): thread containing `handle [UpgradeOp] Upgrade Node[0]`
- old-old: thread containing `No agent connection with executor` (for this run)
- new-new: remaining non-upgrade thread

## Detailed Timing Results
### Round 1 (pool-3, config test12)
- old-old (`pool-3-thread-1`):
  - start: `2026-02-23 07:50:53.574`
  - end: `2026-02-23 07:51:36.247`
  - duration: **42.673 s**
- old-new rolling (`pool-3-thread-2`):
  - start: `2026-02-23 07:50:53.586`
  - end: `2026-02-23 07:54:15.031`
  - duration: **201.445 s**
- new-new (`pool-3-thread-3`):
  - start: `2026-02-23 07:50:53.605`
  - end: `2026-02-23 07:51:39.689`
  - duration: **46.084 s**

### Round 2 (pool-4, config test13)
- old-old (`pool-4-thread-1`):
  - start: `2026-02-23 07:55:36.442`
  - end: `2026-02-23 07:56:19.232`
  - duration: **42.790 s**
- old-new rolling (`pool-4-thread-2`):
  - start: `2026-02-23 07:55:36.468`
  - end: `2026-02-23 07:59:00.846`
  - duration: **204.378 s**
- new-new (`pool-4-thread-3`):
  - start: `2026-02-23 07:55:36.645`
  - end: `2026-02-23 07:56:22.428`
  - duration: **45.783 s**

## Compact Table (seconds)
| Round | old-old | old-new (rolling) | new-new |
|---|---:|---:|---:|
| 1 | 42.673 | 201.445 | 46.084 |
| 2 | 42.790 | 204.378 | 45.783 |

## Notes
- A third packet (`pool-5`) started immediately after round 2, but the run stopped because the script target (`--rounds 2`) was reached. `pool-5` is intentionally excluded from measurements.
- Both rolling executions ended after upgrade-related errors but still reached the `call:344` completion marker; timing above reflects full per-thread execution lifecycle under that run condition.

## Evidence Pointers in Log (line numbers)
From `upfuzz_client_1.log` copy used for analysis (`/tmp/cloudlab_timing_client.log`):
- Round 1 starts: lines 50, 53, 56
- Round 1 ends: lines 139, 151, 170
- Round 1 rolling marker: line 96
- Round 2 starts: lines 221, 227, 233
- Round 2 ends: lines 310, 322, 341
- Round 2 rolling marker: line 267
