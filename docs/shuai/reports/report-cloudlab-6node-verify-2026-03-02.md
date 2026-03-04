# CloudLab 6-Node Verification Run (2026-03-02)

## Scope

- Read `scripts/cloudlab-runner/README.md`.
- Run environment setup on 6 CloudLab machines from `scripts/cloudlab-runner/machine_list.txt`.
- Build mapped rolling-upgrade Docker images.
- Launch verification fuzzing with `testingMode=5` and `rounds=3` for jobs `1..6`.
- Check execution launch count, branch-coverage signals, and network-trace signals.

## Commands Used

- Env setup (all nodes):
  - `scripts/setup-cloudlab/setup_env.sh --mode full --workspace-root /users/swang516/xlab/rupfuzz --upfuzz-dir /users/swang516/xlab/rupfuzz/upfuzz --ssg-runtime-dir /users/swang516/xlab/rupfuzz/ssg-runtime --skip-build --skip-prebuild-check --skip-image-check`
- Verification run (all nodes):
  - `scripts/cloudlab-runner/run_cloudlab_job.sh --job-id <1..6> --rounds 3 --testing-mode 5 --timeout-sec 5400 --skip-docker-build`

## Log Roots

- Setup logs: `/tmp/cloudlab_setup_verify_20260302_113024`
- Verification launcher logs: `/tmp/cloudlab_fuzz_verify_run_20260302_113155`

## Per-Job Outcomes

- Job 1 (Cassandra 3.11.19 -> 4.1.10): `OK`
  - `observed_rounds=3`, `diff_feedback_packets=3`, `trace_signal_ok=true`
  - coverage markers in client log: `24`
- Job 2 (Cassandra 4.1.10 -> 5.0.6): `OK`
  - `observed_rounds=3`, `diff_feedback_packets=3`, `trace_signal_ok=true`
  - coverage markers in client log: `24`
- Job 3 (HBase 2.5.13 -> 2.6.4): `FAIL`
  - `observed_rounds=3`, `diff_feedback_packets=3`, `stop_reason=target_rounds_reached`
  - failure reason: trace requirement gate (`trace_signal_ok=false`)
  - `trace_received_count=0`, `trace_len_positive_count=0`, `trace_len_zero_count=18`
  - repeated lane-null markers: `differential lane ... returned null feedback packet`
  - execution attempts launched (executor starts): `30` (>= 3)
- Job 4 (HBase 2.6.4 -> 3.0.0-beta-1): `FAIL`
  - `observed_rounds=3`, `diff_feedback_packets=3`, `stop_reason=target_rounds_reached`
  - failure reason: trace requirement gate (`trace_signal_ok=false`)
  - `trace_received_count=0`, `trace_len_positive_count=0`, `trace_len_zero_count=18`
  - repeated lane-null markers: `differential lane ... returned null feedback packet`
  - execution attempts launched (executor starts): `30` (>= 3)
- Job 5 (HDFS 2.10.2 -> 3.3.6): `OK`
  - `observed_rounds=3`, `diff_feedback_packets=3`, `trace_signal_ok=true`
  - coverage markers in client log: `36`
- Job 6 (HDFS 3.3.6 -> 3.4.2): `OK`
  - `observed_rounds=3`, `diff_feedback_packets=3`, `trace_signal_ok=true`
  - coverage markers in client log: `36`

## Issues Recorded

1. HBase jobs complete 3 rounds but fail strict trace-signal validation due all-zero traces.
2. HBase startup is flaky on CloudLab during lane restarts (`cannot connect to hbase shell` appears repeatedly), increasing runtime and instability.

## Repo Updates Applied

- `scripts/runner/run_rolling_fuzzing.sh`
  - Added `--hbase-daemon-retry-times` (default `40`) and wired it into generated HBase config (`hbaseDaemonRetryTimes`).
- `scripts/cloudlab-runner/run_cloudlab_job.sh`
  - Added pass-through option `--hbase-daemon-retry-times`.
  - Preserve and copy runner artifacts even when runner exits non-zero.
  - Copy extra marker logs (`server_key_markers.log`, `client_key_markers.log`) into cloudlab result folder.
- `scripts/cloudlab-runner/README.md`
  - Documented the new HBase retry option.
  - Added HBase-specific failure notes for trace-signal and shell-startup flakiness.

## Follow-up Debug: HBase Null Feedback + Zero-Length Trace

### Root Cause

- HBase lanes returned null feedback when `Executor.startup()` failed during node boot.
- In mirror-based prebuild tarballs, `bin/hbase-daemon.sh` is missing, but `src/main/resources/hbase/compile-src/hbase-init.sh` still invoked it directly.
- This produced startup failures (`No such file or directory`) and cascaded to lane-null feedback and zero-length traces.

### Fix Applied

- Updated `src/main/resources/hbase/compile-src/hbase-init.sh`:
  - Added `start_hbase_component()` helper.
  - If `hbase-daemon.sh` exists, keep original path.
  - If missing, fall back to `${HBASE_HOME}/bin/hbase --config ... <component> start`.
  - Added process readiness check via `jps` (`HQuorumPeer`, `HMaster`, `HRegionServer`) with explicit failure if JVM does not appear.
- Synced patch to CloudLab repos on job 3 and job 4 hosts.
- Rebuilt images:
  - `upfuzz_hbase:hbase-2.5.13_hbase-2.6.4`
  - `upfuzz_hbase:hbase-2.6.4_hbase-3.0.0-beta-1`

### Verification Rerun (In Progress at Capture Time)

- Launcher root: `/tmp/cloudlab_hbase_fix_verify_20260302_130700`
- Commands used on job 3/job 4:
  - `scripts/cloudlab-runner/run_cloudlab_job.sh --job-id <3|4> --rounds 3 --testing-mode 5 --timeout-sec 5400 --skip-docker-build --skip-build`
- Observations during rerun:
  - Job 3: monitor reached `rounds=1`, `diff_feedback_packets=1`.
  - Job 4: monitor reached `rounds=1`, `diff_feedback_packets=1`.
  - Both jobs showed:
    - `feedback packet sent to server` present.
    - `Received trace = ...` present (non-zero trace collection path active).
    - no new `returned null feedback packet` markers observed in current logs.

### Remaining Stability Issue

- HBase `2.6.4 -> 3.0.0-beta-1` can still hit readiness timeout in some lanes:
  - `HBase rolling upgrade timed out waiting for control-plane readiness`
  - frequent `zkTransient=true` readiness probes and occasional `Connection refused` to `hregion1:16000`
- This is a runtime upgrade stability issue, separate from the fixed null-feedback/zero-trace root cause.
