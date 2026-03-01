# CloudLab SCP-Only Setup + Cassandra Demo Verification (2026-02-23)

## Target Host
- `swang516@c220g5-110417.wisc.cloudlab.us`
- Workspace root on host: `/users/swang516/rupfuzz`

## Scope
- Validate brand-new host setup in SCP-only mode (no remote git clone).
- Run Cassandra rolling-upgrade fuzzing demo: `apache-cassandra-4.1.10 -> apache-cassandra-5.0.6`.
- Fix blocking issues encountered during setup/demo.

## What Was Uploaded (SCP/rsync)
- `upfuzz-shuai` -> `/users/swang516/rupfuzz/upfuzz-shuai`
- `ssg-runtime-shuai` -> `/users/swang516/rupfuzz/ssg-runtime-shuai`
- `nettrace-shuai/rupfuzz-nettrace/scripts/instrument_prebuild_matrix.sh`
- Updated Cassandra instrumented tarballs:
  - `/users/swang516/rupfuzz/upfuzz-shuai/prebuild/cassandra/apache-cassandra-4.1.10-src-instrumented.tar.gz`
  - `/users/swang516/rupfuzz/upfuzz-shuai/prebuild/cassandra/apache-cassandra-5.0.6-src-instrumented.tar.gz`

## Setup Script Fixes Applied
File: `scripts/setup-cloudlab/setup_env.sh`

1. Added SCP-only mode:
- `--use-existing-repos`
- Skips clone/fetch/pull and uses uploaded directories directly.

2. Fixed no-`.git` build path:
- In `--use-existing-repos` mode with missing `.git`, use `./gradlew assemble -x test` for UpFuzz instead of `build`, avoiding Spotless git-repo requirement.

3. Fixed silent early exit bug:
- Replaced `[[ -n "${PREBUILD_SOURCE_DIR}" ]] || return` with `... || return 0` in copy helper functions.
- This prevented `set -e` from terminating setup when `PREBUILD_SOURCE_DIR` is empty.

## Setup Result
Command executed:
- `scripts/setup-cloudlab/setup_env.sh --mode cassandra-demo --workspace-root /users/swang516/rupfuzz --upfuzz-dir /users/swang516/rupfuzz/upfuzz-shuai --ssg-runtime-dir /users/swang516/rupfuzz/ssg-runtime-shuai --use-existing-repos --pull-images --image-prefix shuaiwang516`

Result:
- `SETUP_EXIT=0`
- Script reached `Environment setup completed.`

## Demo Blocker Found and Fixed
### Initial failure symptom
- Rolling demo got stuck with repeated node restarts and no progress (`rounds=0`).
- Container logs showed startup crashes on node-1.

### Root cause
- Null dereference in instrumented bridge:
- `NetTraceRuntimeBridge.detectChannel(...)` called `arg.getClass()` on null context arg.

Observed stack traces (container `system.log`) included:
- `java.lang.NullPointerException`
- at `org.apache.cassandra.net.NetTraceRuntimeBridge.detectChannel(NetTraceRuntimeBridge.java:340)`
- during `MessagingService.doSend(...)` at startup.

### Fix applied
- Added null-safety/guarding for `contextArgs` and `arg == null` in bridge helper methods.
- Updated template generator:
  - `nettrace-shuai/rupfuzz-nettrace/scripts/instrument_prebuild_matrix.sh`
- Updated Cassandra extracted sources:
  - `prebuild/cassandra/apache-cassandra-4.1.10/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java`
  - `prebuild/cassandra/apache-cassandra-5.0.6/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java`
  - `prebuild/cassandra/apache-cassandra-3.11.19/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java`

### Rebuild performed on host
1. Recompiled Cassandra sources:
- `apache-cassandra-4.1.10` (JDK11, `ant ... jar`)
- `apache-cassandra-5.0.6` (JDK17, `ant ... jar`)

2. Rebuilt rolling image:
- `scripts/docker/build_rolling_image_pair.sh cassandra apache-cassandra-4.1.10 apache-cassandra-5.0.6`
- New local image:
  - `upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6`
  - image id: `88555193d10e`

## Final Demo Run (Successful)
Run command:
- `scripts/runner/run_rolling_fuzzing.sh --system cassandra --original apache-cassandra-4.1.10 --upgraded apache-cassandra-5.0.6 --rounds 2 --require-trace-signal --run-name cassandra_410_to_506_remote_demo_fix1`

Result summary file:
- `/users/swang516/rupfuzz/upfuzz-shuai/scripts/runner/results/cassandra_410_to_506_remote_demo_fix1/summary.txt`

Key outcomes:
- `observed_rounds: 2`
- `diff_feedback_packets: 2`
- `stop_reason: target_rounds_reached`
- `trace_signal_ok: true`
- `trace_received_count: 18`
- `trace_len_positive_count: 12`
- `trace_len_zero_count: 0`
- `trace_connect_refused_count: 0`

Runtime:
- `duration_sec: 378`
- Start: `2026-02-23 18:17:17`
- End: `2026-02-23 18:23:35`

## Trace/Server Verification Highlights
From `server_stdout.log` in run result directory:
- Two `TestPlanDiffFeedbackPacket received` events observed.
- Positive trace lengths for old/rolling/new packets.
- Message-identity analysis active:
  - `Message identity tri-diff: MessageTriDiff{...}`
  - `Message identity tri-diff detected interesting divergence`

From `client_launcher_stdout.log`:
- `trace diff: all three packets are collected` observed for both rounds.
- No `NullPointerException` from bridge after fix.

## Artifacts
- Setup log: `/users/swang516/rupfuzz/setup_env_foreground.log`
- Rebuild log: `/users/swang516/rupfuzz/rebuild_fix.log`
- Demo log: `/users/swang516/rupfuzz/cassandra_demo_fix1.log`
- Result dir: `/users/swang516/rupfuzz/upfuzz-shuai/scripts/runner/results/cassandra_410_to_506_remote_demo_fix1`
