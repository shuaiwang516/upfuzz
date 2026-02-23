# Cassandra Instrumented Trace Verification (CloudLab)

Date: 2026-02-23
Server: `swang516@c220g5-110417.wisc.cloudlab.us`

## 1) Instrumented source upload and hook check

Uploaded archives to remote:
- `/users/swang516/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-4.1.10-src-instrumented.tar.gz`
- `/users/swang516/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-5.0.6-src-instrumented.tar.gz`

Verified hook injection exists in both versions:
- `src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java`
- `MessagingService.java` calls `NetTraceRuntimeBridge.recordSend(...)`
- `InboundMessageHandler.java` calls `NetTraceRuntimeBridge.beginReceive(...)`/`endReceive(...)`

Verified compiled classes include `NetTraceRuntimeBridge.class` in Cassandra jar and runtime class in `ssgFatJar.jar`:
- `org/apache/cassandra/net/NetTraceRuntimeBridge.class`
- `org/zlab/net/tracker/Runtime.class`

## 2) About `java.net.ConnectException: Connection refused`

Result:
- For `node_num=1` with `useTrace=true`, `Connection refused` repeatedly occurred during `collectTrace`, and server showed `trace[0] len = 0` for old/rolling/new.
- This did **not** immediately crash the round because exceptions are logged and swallowed in `DockerCluster.collectTrace(...)`, but trace signal is effectively missing.

Interpretation:
- Not acceptable for trace verification.
- With this instrumentation path, trace runtime/listener may not be initialized in single-node runs where needed inter-node messaging hooks are absent/minimal.

## 3) Network trace verified working with instrumented source

Successful strict run:
- Run dir: `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cass_trace_strict_node2_after_regex_fix`
- Command used: runner with `--node-num 2 --use-trace true --require-trace-signal --rounds 1`

Summary evidence (`summary.txt`):
- `trace_signal_ok: true`
- `trace_connect_refused_count: 0`
- `trace_received_count: 9`
- `trace_len_positive_count: 6`
- `trace_len_zero_count: 0`

Server log evidence (`upfuzz_server.log`):
- `trace[0] len = 1251`
- `trace[1] len = 1260`
- `trace[0] len = 1210`
- `trace[1] len = 1208`
- `trace[0] len = 1404`
- `trace[1] len = 1385`

Client log evidence (`upfuzz_client_1.log`):
- `Received trace = org.zlab.net.tracker.Trace@...`
- `trace diff: all three packets are collected`

Live container evidence during run:
- Listener active: `0.0.0.0:62000`
- `/tmp/coverage.log` present and populated with entries including:
  - `NETTRACE SEND name=MessagingService.doSend id=4110001 ...`
  - `NETTRACE RECV_BEGIN name=InboundMessageHandler.ProcessMessage.run id=4210001 ...`
  - `NETTRACE RECV_END name=InboundMessageHandler.ProcessMessage.run id=4210001 ...`

## 4) Script fixes applied

### `scripts/runner/run_rolling_fuzzing.sh`
Added trace health instrumentation and strict verification mode:
- new flag: `--require-trace-signal`
- summary fields:
  - `trace_signal_ok`
  - `trace_connect_refused_count`
  - `trace_received_count`
  - `trace_len_positive_count`
  - `trace_len_zero_count`
- strict failure behavior when enabled:
  - fail if no trace signal
  - fail if any `Connection refused`
- warning when `--use-trace true` and Cassandra `node-num < 2`

### `scripts/setup-cloudlab/setup_env.sh`
Changed demo setup to use instrumented prebuild archives instead of downloading non-instrumented Apache binaries:
- new/updated options:
  - `--mode <cassandra-demo|full>`
  - `--skip-demo-prebuild-materialize` (keeps backward alias `--skip-demo-prebuild-download`)
- cassandra-demo flow now:
  - copies demo instrumented archives from `--prebuild-source-dir` when provided
  - extracts instrumented source archives
  - materializes Cassandra build via `ant` (4.1.10 on Java 11, 5.0.6 on Java 17)
  - ensures `ssgFatJar.jar` and daemon files are in place
  - validates hook file exists: `NetTraceRuntimeBridge.java`
- prebuild checks now detect missing/mis-instrumented demo directories and provide actionable errors.

