# Plan: Run Rolling-Upgrade Fuzzing (Demo: Cassandra 4.1.10 -> 5.0.6)

## Goal
Run a **differential rolling-upgrade fuzzing demo** for Cassandra `apache-cassandra-4.1.10 -> apache-cassandra-5.0.6`, then reuse the same workflow for all prepared Cassandra/HDFS/HBase version pairs.

This plan is execution-focused (what to run, what to verify, and how to scale to the rest of the matrix).

---

## What the code path does (confirmed)

1. `bin/start_server.sh` and `bin/start_clients.sh` start `Main` with `-class server|client` and `-config <file>`.
2. In the client, `executeTestPlanPacket(...)` selects differential mode when `differentialExecution=true`.
3. Differential mode runs **3 clusters in parallel**:
   - Cluster 0: old-old (no real upgrade, upgrade ops replaced with restart)
   - Cluster 1: rolling (real upgrade events)
   - Cluster 2: new-new (no real upgrade, starts from new version)
4. Client returns `TestPlanDiffFeedbackPacket` to server.
5. Server `updateStatus(TestPlanDiffFeedbackPacket)` checks:
   - 3-packet structure
   - trace-based Jaccard/EditDistance (if enabled)
   - branch coverage updates from old/rolling/new
   - rolling-only failures, cross-cluster inconsistency, per-cluster inconsistency, error logs.

Relevant implementation files:
- `src/main/java/org/zlab/upfuzz/fuzzingengine/FuzzingClient.java`
- `src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java`
- `src/main/java/org/zlab/upfuzz/fuzzingengine/FuzzingClientSocket.java`
- `src/main/java/org/zlab/upfuzz/fuzzingengine/Config.java`

---

## Phase 0: Preflight (one-time before the demo)

Run from repo root:

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz-shuai

# 0.1 Confirm the target image exists
docker images --format '{{.Repository}}:{{.Tag}}' | rg 'upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6'

# 0.2 Confirm runtime prebuild dirs exist
test -d prebuild/cassandra/apache-cassandra-4.1.10
test -d prebuild/cassandra/apache-cassandra-5.0.6

# 0.3 Ensure java artifacts/dependencies are ready
./gradlew copyDependencies
./gradlew build -x test

# 0.4 Clean any prior leftover processes/containers
bin/clean.sh --force
```

Notes:
- Use unique `serverPort` / `clientPort` if other users are running UpFuzz.
- Keep one run per terminal session first (avoid parallel runs until baseline is stable).

---

## Phase 1: Demo config (Cassandra 4.1.10 -> 5.0.6)

Create a dedicated config for differential rolling-upgrade smoke verification (`testingMode=3`).

### 1.1 Create config file

```bash
cat > cass_demo_4_1_10_to_5_0_6_diff_mode3.json <<'JSON'
{
  "originalVersion" : "apache-cassandra-4.1.10",
  "upgradedVersion" : "apache-cassandra-5.0.6",
  "system" : "cassandra",
  "serverPort" : "7399",
  "clientPort" : "7400",
  "configDir" : "configtests",

  "STACKED_TESTS_NUM" : 1,
  "STACKED_TESTS_NUM_G2" : 1,
  "DROP_TEST_PROB_G2" : 0.1,
  "sequenceMutationEpoch" : 80,
  "nodeNum" : 2,
  "faultMaxNum" : 0,

  "loadInitCorpus" : false,
  "saveCorpusToDisk" : true,
  "testSingleVersion" : false,

  "testingMode" : 3,
  "differentialExecution" : true,

  "useTrace" : true,
  "printTrace" : false,
  "useJaccardSimilarity" : true,
  "jaccardSimilarityThreshold" : 0.3,
  "useBranchCoverage" : true,
  "enableLogCheck" : true,

  "useFormatCoverage" : false,
  "useVersionDelta" : false,
  "verifyConfig" : false,
  "debug" : false,
  "useExampleTestPlan" : false,
  "startUpClusterForDebugging" : false,
  "drain" : false,
  "useFixedCommand" : false,

  "enable_ORDERBY_IN_SELECT" : true,
  "cassandraEnableTimeoutCheck" : false,
  "CASSANDRA_RETRY_TIMEOUT" : 300
}
JSON
```

### 1.2 Why `testingMode=3` first

- It is the fastest way to verify the differential 3-cluster pipeline end-to-end.
- Server sends a test plan immediately (`generateExampleTestplanPacket()`), so no prerequisite corpus is needed.

---

## Phase 2: Launch demo run

### 2.1 Start server + client (tmux recommended)

```bash
tmux kill-session -t cass-410-506-demo 2>/dev/null || true

# window 0: server, window 1: client
tmux new-session -d -s cass-410-506-demo '\
  cd /home/shuai/xlab/rupfuzz/upfuzz-shuai && \
  bin/start_server.sh cass_demo_4_1_10_to_5_0_6_diff_mode3.json > logs/cass_demo_410_506_server.log 2>&1'

tmux split-window -t cass-410-506-demo -v '\
  cd /home/shuai/xlab/rupfuzz/upfuzz-shuai && \
  sleep 3 && \
  bin/start_clients.sh 1 cass_demo_4_1_10_to_5_0_6_diff_mode3.json > logs/cass_demo_410_506_client_launcher.log 2>&1'

# optional attach
tmux attach -t cass-410-506-demo
```

### 2.2 Runtime log files to watch

- `logs/upfuzz_server.log`
- `logs/upfuzz_client_1.log`
- `logs/cass_demo_410_506_server.log`

---

## Phase 3: Verify differential rolling-upgrade behavior (must pass)

Run these checks while/after run:

```bash
# 3.1 Client entered differential testplan path
rg -n "executeTestPlanPacketDifferential|trace diff: all three packets are collected" logs/upfuzz_client_1.log

# 3.2 Server received differential feedback packet
rg -n "TestPlanDiffFeedbackPacket received" logs/upfuzz_server.log

# 3.3 Differential similarity logic executed
rg -n "Jaccard Similarity\[0\]|Low Jaccard similarity|Added test plan to corpus" logs/upfuzz_server.log

# 3.4 Differential labels visible in reporting
rg -n "Only Old|Rolling|Only New" logs/upfuzz_server.log

# 3.5 Optional: confirm running containers use target image
docker ps --format '{{.Names}}\t{{.Image}}' | rg 'upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6'

# 3.6 If failures detected, inspect artifacts
find failure -maxdepth 3 -type f | sort | tail -n 30
```

Expected signal:
- Server log includes `TestPlanDiffFeedbackPacket received` repeatedly.
- Client log includes `executeTestPlanPacketDifferential`.
- Trace similarity and/or coverage updates are logged.

Stop and cleanup:

```bash
bin/clean.sh --force
tmux kill-session -t cass-410-506-demo 2>/dev/null || true
```

---

## Phase 4: Convert demo to sustained rolling-upgrade fuzzing

`testingMode=3` is a deterministic pipeline check. For sustained rolling-upgrade fuzzing use `testingMode=5`.

### 4.1 Create mode-5 config (rolling-only fuzzing)

```bash
cp cass_demo_4_1_10_to_5_0_6_diff_mode3.json cass_demo_4_1_10_to_5_0_6_diff_mode5.json
sed -i 's/"testingMode" : 3/"testingMode" : 5/' cass_demo_4_1_10_to_5_0_6_diff_mode5.json
```

### 4.2 Seed availability rule (important)

In `testingMode=5`, server tries to mutate rolling test plans from full-stop seeds. If no seeds exist, it falls back to example test plan.

Recommended path:
1. Warm up corpus with a short full-stop run (`testingMode=0`, same version pair, `saveCorpusToDisk=true`).
2. Restart with `testingMode=5`, `differentialExecution=true`, and optionally `loadInitCorpus=true`.

This gives better rolling-plan diversity than relying only on example plans.

---

## Phase 5: Reuse for all prepared version pairs

Use the same workflow and only swap system/version/config-specific fields.

### 5.1 Version-pair matrix

| System | Original -> Upgraded | Required Docker image tag |
|---|---|---|
| Cassandra | `apache-cassandra-3.11.19 -> apache-cassandra-4.1.10` | `upfuzz_cassandra:apache-cassandra-3.11.19_apache-cassandra-4.1.10` |
| Cassandra | `apache-cassandra-4.1.10 -> apache-cassandra-5.0.6` | `upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6` |
| HDFS | `hadoop-2.10.2 -> hadoop-3.3.6` | `upfuzz_hdfs:hadoop-2.10.2_hadoop-3.3.6` |
| HDFS | `hadoop-3.3.6 -> hadoop-3.4.2` | `upfuzz_hdfs:hadoop-3.3.6_hadoop-3.4.2` |
| HBase | `hbase-2.5.13 -> hbase-2.6.4` | `upfuzz_hbase:hbase-2.5.13_hbase-2.6.4` |
| HBase | `hbase-2.6.4 -> hbase-3.0.0-beta-1` | `upfuzz_hbase:hbase-2.6.4_hbase-3.0.0-beta-1` |

### 5.2 System-specific config notes

1. Cassandra:
- Base from `cass_diff_config.json`.
- Keep `nodeNum=2` for rolling differential runs.

2. HDFS:
- Base from `hdfs_config.json`.
- Keep `system="hdfs"`, `nodeNum=3` (repo default), and set `testingMode`/`differentialExecution` same way.
- Use unique ports per run.

3. HBase:
- Base from `hbase_config.json`.
- Keep `depSystem="hadoop"`, `depVersion="hadoop-2.10.2"`.
- Ensure dependency image exists: `upfuzz_hdfs:hadoop-2.10.2`.

### 5.3 Required common toggles for differential rolling-upgrade verification

For every pair config:

```json
"testSingleVersion": false,
"testingMode": 3,
"differentialExecution": true,
"useTrace": true,
"useJaccardSimilarity": true,
"useBranchCoverage": true,
"enableLogCheck": true
```

After smoke verification passes, switch `testingMode` to `5` for sustained rolling fuzzing.

---

## Phase 6: Phase-4 verification checklist for each pair

For each version pair, mark run as verified only if all checks below pass:

1. Differential packet path confirmed:
- client log has `executeTestPlanPacketDifferential`
- server log has `TestPlanDiffFeedbackPacket received`

2. Three-cluster semantics confirmed:
- server log shows old/rolling/new handling (`Only Old`, `Rolling`, `Only New`)

3. Differential metrics confirmed:
- server log prints Jaccard (or edit-distance if enabled)

4. Runtime stability:
- no immediate cluster startup crash loop
- no persistent null feedback packet

5. Artifacts collected:
- save `logs/upfuzz_server.log`, `logs/upfuzz_client_1.log`
- archive `failure/` subdirs if any

Recommended archive command per run:

```bash
SYSTEM='cassandra'
ORI='apache-cassandra-4.1.10'
UP='apache-cassandra-5.0.6'
RUN_TAG="${SYSTEM}_${ORI}_to_${UP}_$(date +%Y%m%d_%H%M%S)"
mkdir -p logs/runs/${RUN_TAG}
cp -f logs/upfuzz_server.log logs/runs/${RUN_TAG}/ || true
cp -f logs/upfuzz_client_1.log logs/runs/${RUN_TAG}/ || true
```

---

## Quick command template for later runs

```bash
# 1) Cleanup
bin/clean.sh --force

# 2) Start server
bin/start_server.sh <config.json> > logs/<run>_server.out 2>&1 &

# 3) Start one client
sleep 2
bin/start_clients.sh 1 <config.json>

# 4) Verify differential path
rg -n "TestPlanDiffFeedbackPacket received|Jaccard Similarity" logs/upfuzz_server.log
rg -n "executeTestPlanPacketDifferential" logs/upfuzz_client_1.log

# 5) Cleanup
bin/clean.sh --force
```
