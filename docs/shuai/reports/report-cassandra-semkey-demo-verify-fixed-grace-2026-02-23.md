# Cassandra 4.1.10 -> 5.0.6 Rolling Demo Verification (Semantic Key + Runner Grace)

Date: 2026-02-23

## Scope
This report verifies a full 2-round rolling-upgrade fuzzing demo for Cassandra (`apache-cassandra-4.1.10` -> `apache-cassandra-5.0.6`) after applying message-identity semantic-key changes and fixing run reliability issues.

## Root Cause Found During Verification
An initial rerun failed to progress because `N1` nodes repeatedly crashed during startup with:
- `java.lang.NullPointerException`
- `org.apache.cassandra.net.NetTraceRuntimeBridge.detectChannel(...)`

Cause:
- Prebuild source had null-safe `NetTraceRuntimeBridge`, but compiled Cassandra jars were stale (built from older bridge code without null checks), due `.upfuzz_materialized` marker causing build skip.

Evidence:
- Before rebuild, `javap` on `NetTraceRuntimeBridge.detectChannel` showed direct `arg.getClass()` with no null guard.
- After rebuild, `javap` shows explicit null checks for `contextArgs` and each `arg`.

## Fixes Applied

### 1) Rebuild freshness fix for Cassandra materialization
File:
- `scripts/docker/build_rolling_image_pair.sh` (wrapper; now dispatches to per-system docker build scripts)

Change:
- `materialize_cassandra_version()` now rebuilds Cassandra when:
  - marker missing, or
  - built jar missing, or
  - `NetTraceRuntimeBridge.java` newer than marker, or
  - `lib/ssgFatJar.jar` newer than marker.

Impact:
- Prevents stale compiled bridge/runtime combinations in future image builds.

### 2) Runner stop-condition grace for tri-diff completion
File:
- `scripts/runner/run_rolling_fuzzing.sh`

Change:
- When target rounds are reached and trace is enabled, runner now waits (up to 120s) for `Message identity tri-diff:` lines to reach `TARGET_ROUNDS` before stopping.

Impact:
- Avoids stopping immediately after packet receipt and missing tri-diff lines for the last round.

## Demo Runs

### A) Post-rebuild verification run (before runner grace fix)
Run dir:
- `scripts/runner/results/cassandra_4_1_10_to_5_0_6_msg_identity_semkey_verify_fixed_2026_02_24`

Outcome:
- 2 diff packets reached, but last-round tri-diff line could be truncated due immediate stop.
- This motivated runner grace fix.

### B) Final verified run (with runner grace fix)
Run dir:
- `scripts/runner/results/cassandra_4_1_10_to_5_0_6_msg_identity_semkey_verify_fixed_grace_2026_02_24`

Summary (`summary.txt`):
- `observed_rounds: 2`
- `diff_feedback_packets: 2`
- `stop_reason: target_rounds_reached`
- `duration_sec: 476`
- `trace_enabled: true`
- `trace_signal_ok: true`
- `trace_received_count: 18`
- `trace_len_positive_count: 12`

## Tri-diff Evidence (Both Rounds)
From `logs/upfuzz_server.log` and run `server_stdout.log`:

Round 1:
- `MessageTriDiff{len0=770, len1=780, len2=755, all3=748, only0=7, only1=15, only2=5, in01Only=15, in12Only=2, in02Only=0, lcs012=719, orderRatio=0.9523}`

Round 2:
- `MessageTriDiff{len0=850, len1=832, len2=832, all3=819, only0=17, only1=3, only2=7, in01Only=9, in12Only=1, in02Only=5, lcs012=781, orderRatio=0.9387}`

Both rounds now produce message-identity tri-diff output (count=2), confirming runner grace behavior and end-to-end trace diff processing.

## Verification Conclusion
The Cassandra rolling demo is verified end-to-end after fixes:
1. Stale bridge compilation issue resolved by freshness-based rebuild in docker build flow.
2. Runner now preserves final-round tri-diff logging before stopping.
3. Final 2-round run completed with valid trace signals and tri-diff summaries for both rounds.
