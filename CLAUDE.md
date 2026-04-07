# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

UpFuzz is a coverage-guided fuzzing tool for detecting data format incompatibility bugs during distributed system upgrades. It tests systems like Cassandra, HDFS, HBase, and Ozone during version upgrades.

## Build Commands

```bash
# Build the project
./gradlew build

# Apply code formatting (required before commits)
./gradlew :spotlessApply

# Copy dependencies to dependencies/ folder (required before first run)
./gradlew copyDependencies

# Full build with formatting
./gradlew :spotlessApply build

# Run tests
./gradlew test

# Build with Nyx snapshot mode support (compiles C components)
./gradlew nyxBuild
```

## Running UpFuzz

```bash
# Start fuzzing server (run in tmux or separate terminal)
bin/start_server.sh <config.json>

# Start fuzzing clients
bin/start_clients.sh <NUM_CLIENTS> <config.json>

# Stop testing and cleanup
bin/clean.sh           # Cassandra
bin/hdfs_cl.sh         # HDFS
bin/hbase_cl.sh        # HBase
bin/ozone_cl.sh V1 V2  # Ozone
```

Configuration files: `config.json`, `cass4_config.json`, `cass5_config.json`, `hdfs_config.json`, `hbase_config.json`, `ozone_config.json`

## Architecture

### Package Structure (`src/main/java/org/zlab/upfuzz/`)

- **fuzzingengine/**: Core fuzzing orchestration
  - `Main.java`: Entry point (`-class server|client|fuzzer`)
  - `FuzzingServer.java`: Aggregates coverage, generates test cases, detects failures
  - `FuzzingClient.java`: Executes tests against target systems, reports feedback
  - `Config.java`: JSON configuration parsing
  - `packet/`: Test packet types (TestPacket, StackedTestPacket)
  - `testplan/event/`: Events including commands, faults, upgrade/downgrade operations

- **System-specific implementations**:
  - `cassandra/`: CQL commands (`cqlcommands/`), nodetool commands
  - `hdfs/`: DFS commands (`dfs/`), admin commands (`dfsadmin/`), erasure coding (`ec/`)
  - `hbase/`: DDL (`ddl/`), DML (`dml/`), snapshots, namespaces, security
  - `ozone/`: Shell commands (`sh/bucket/`, `sh/key/`, `sh/volume/`)

- **nyx/**: Nyx snapshot mode integration (JNI interface to Nyx library for QEMU-based VM snapshots)

### Key Abstractions

Each supported system implements:
- `*Executor.java`: Manages Docker containers and command execution
- `*CommandPool.java`: Defines available commands for the system
- Individual command classes implementing the type system for generation/mutation

### Coverage & Oracles

- JaCoCo-based branch coverage (custom fork at `com.github.hanke580.jacoco`)
- Format coverage via `dinv-monitor` instrumentation (experimental)
- Network trace coverage via `ssg-runtime` instrumentation (in development)
- Failure detection: exception/error logs, read inconsistency comparison

## Configuration Parameters

Key parameters in config JSON files:
- `system`: cassandra|hdfs|hbase|ozone
- `originalVersion`, `upgradedVersion`: Version strings
- `testSingleVersion`: true for single-version testing, false for upgrade testing
- `testingMode`: 0 (stacked tests), 3 (example test plans), or 4 (stacked tests + test plans with fault injection)
- `nodeNum`: Number of cluster nodes
- `serverPort`, `clientPort`: Ports for server-client communication
- `differentialExecution`: true to run 3 clusters (Old-Old, Rolling, New-New) and compare
- `useTrace`: true to collect network traces from instrumented nodes
- `useCanonicalTraceSimilarity`: true to use windowed canonical trace similarity for corpus admission
- `useCompressedOrderDebug`: true to log compressed per-flow order similarity (debug only, no admission effect)
- `useFormatCoverage`: true to enable format coverage tracking
- `printTrace`: true to print full trace entries in server log (verbose)

## Daemon Scripts

Daemons save JVM startup time by keeping a persistent process:
- **Cassandra**: `cqlsh_daemon2.py` (2.2-4.0), `cqlsh_daemon4.py` (4.x), `cqlsh_daemon5.py` (5.x)
- **HDFS**: `FsShellDaemon2.java` (2.x), `FsShellDaemon_trunk.java` (>=3.3.4)
- **HBase**: `hbase_daemon2.py` (<2.4.0), `hbase_daemon3.py` (>=2.4.0)

## Docker Integration

Docker images follow naming pattern: `upfuzz_{system}:{ori_version}_{up_version}`

Build from `src/main/resources/{system}/*/compile-src/`

### Docker Image Requirements for Differential Execution

For differential execution with versions A → B, three clusters are created, Image `upfuzz_cassandra:oldVersion_newVersion`:
1. **Old-Old cluster (direction=0)**
2. **Rolling cluster (direction=0)**
3. **New-New cluster (direction=1)**

`CassandraDocker.java:34-39` swaps originalVersion/upgradedVersion when direction=1.

## Output

- `failure/`: Detected bugs with logs and reproduction info
- `corpus/`: Fuzzing corpus
- `logs/`: Runtime logs

## References

- **`artifact/`**: Contains existing scripts and instructions for running experiments (e.g., `test-cass.sh`, `test-hdfs.sh`, `setup-upfuzz-env.sh`). **Note:** These scripts are designed for CloudLab machines with Docker container volume mounts on specific paths, so if testing locally, be aware of path differences and adjust accordingly.
- **`docs/`**: Contains extensive documentation on configuration, system internals, and implementation plans for reference.

---

## Related Repositories (under /home/shuai/xlab/rupfuzz/)

UpFuzz depends on three sibling repositories for instrumentation and runtime tracking:

### ssg-runtime

Runtime library packaged as `ssgFatJar.jar`. Contains two independent tracking systems:

- **`org.zlab.ocov.tracker.Runtime`** — Format coverage tracking
  - `init()`: Initialize format coverage, start server on port 62000
  - `update(Object, int, Object...)`: Track serialized object at merge point
  - `updateBranch(Object, Object, String, int)`: Track boundary branch condition
  - `monitorCreationContext(Object, int)`: Track object creation context

- **`org.zlab.net.tracker.Runtime`** — Network trace tracking
  - `init()`: Initialize trace, start server on port 62000
  - `hit(int id)`: Record block ID in per-thread ring buffer (K=128)
  - `snapshot()`: Capture K most recent block IDs
  - `record(String name, int id, Object... contextArgs)`: Record message send/recv with execution path snapshot
  - `clear()`: Reset trace and ring buffer

- **`org.zlab.net.tracker.Trace`** — Trace data structure
  - `record()`: Creates TraceEntry with verb name, execution path hash, changedMessage flag, timestamp
  - `getCanonicalMultiset()`: Returns multiset of canonical message keys (for similarity)
  - `getCanonicalKeysForDiff()`: Returns ordered canonical keys (for tri-diff)
  - `mergeBasedOnTimestamp()`: Merges traces from multiple nodes by timestamp
  - `examineChangedMessage()`: Uses ObjectGraphTraverser to check if payload contains modified classes

- **`org.zlab.net.tracker.diff.DiffComputeSemanticSimilarity`** — Canonical similarity metric
  - Weighted multiset Jaccard on canonical message keys
  - `compute(trace0, trace1, trace2)` returns `double[3]`: [OO-RO, RO-NN, OO-NN]

- **`org.zlab.net.tracker.diff.DiffComputeCompressedOrder`** — Debug order metric
  - Per-flow compressed sequence similarity via normalized LCS
  - `compute(trace0, trace1, trace2)` returns `double[3]`: [OO-RO, RO-NN, OO-NN]

Build:
```bash
cd /home/shuai/xlab/rupfuzz/ssg-runtime
./gradlew shadowJar  # produces build/libs/ssgFatJar.jar
```

### dinv-monitor

Source-level instrumentation tool using JavaParser AST rewriting. Three instrumentation passes:

- **InstDumpPoint**: Injects `Runtime.update()` at serialization merge points
- **InstCreationPoint**: Injects `Runtime.monitorCreationContext()` at object creation sites
- **InstBoundaryBranchPoint**: Wraps if-conditions with `Runtime.updateBranch()` at boundary branches

Also generates `modifiedFields.json` (classes with changed fields between versions):
```bash
cd /home/shuai/xlab/rupfuzz/dinv-monitor
./gradlew modifiedFields --args="-infoPath output \
  -targetOldSystemPath <old>/src/java \
  -targetNewSystemPath <new>/src/java \
  -tp org.apache.cassandra"
```

Instrumentation invocation:
```bash
./gradlew instDumpPoints --args="-targetSystemPath <system>/src/java \
  -dumpPointsPath <vasco-analysis-output>"
```

Shell scripts in `dinv-scripts/`: `cass-src-inst.sh`, `hdfs-src-inst-all.sh`, `hbase-src-inst-all.sh`

### vasco

Soot-based static analysis and bytecode instrumentation:

- **Static analysis** (Soot dataflow): Identifies serialization merge points, boundary branches, creation contexts. Outputs JSON files consumed by dinv-monitor:
  - `mergePoints_alg1.json`: `Map<ClassName, Map<LineNumber, Set<MergePointInfo>>>`
  - `allBoundaryBranchLocations.json`: `Map<ClassName, Set<LineNumber>>`
  - `topObjectCreationPoints.json`
  - `serializedFields_alg1.json`

- **Bytecode instrumentation** (OutputStreamInstrumenter): Injects `Runtime.update()` at OutputStream.write() call sites identified by merge point analysis. Uses Soot/Shimple IR.

Pre-computed analysis output exists at:
- `vasco/system-sut/cassandra/apache-cassandra-3.11.17/allBoundaryBranchLocations.json`
- `vasco/system-sut/cassandra/apache-cassandra-4.1.6/allBoundaryBranchLocations.json`

---

## Network Trace Guided Fuzzing (Differential Execution)

### Concept: (DEF, MSG, USE) Tuples

Each network message is characterized by three components:
- **DEF** (Definition): Execution path before send — ring buffer of K=128 recent block IDs
- **MSG** (Message): Message type and content — verb name, payload types, changedMessage flag
- **USE** (Usage): Execution path after receive — handler code path (NOT YET IMPLEMENTED)

### How Differential Execution Works

1. Same test runs against 3 clusters simultaneously:
   - **Old-Old** (direction=0): Both nodes stay on old version
   - **Rolling** (direction=0): One node upgrades mid-test
   - **New-New** (direction=1): Both nodes start on new version
2. Network traces collected from all 3 clusters (port 62000)
3. Traces merged by timestamp across nodes within each cluster
4. Jaccard similarity computed: Sim[0] = Old-Old vs Rolling, Sim[1] = Rolling vs New-New
5. Rolling is the pivot — both comparisons are relative to it

### Key Code Path

```
FuzzingClient.executeTestPlanPacket()
  └─ if (Config.differentialExecution)
       └─ executeTestPlanPacketDifferential()
            └─ Creates 3 executors with directions {0, 0, 1}
            └─ Each executor runs test, collects traces
            └─ Results sent as TestPlanDiffFeedbackPacket

FuzzingServer.updateStatus(TestPlanDiffFeedbackPacket)
  └─ Merges traces from each cluster's nodes
  └─ Computes Jaccard similarity (logged but NOT fed to corpus)
  └─ testPlanID2Setup: {0="Only Old", 1="Rolling", 2="Only New"}
```

### Current TraceEntry Structure

```java
TraceEntry {
    int id;                    // 1=SEND, 2=RECV
    String methodName;         // "SEND_MUTATION", "RECV_GOSSIP_DIGEST_SYN"
    int hashcode;              // Currently: name.hashCode() only
    boolean changedMessage;    // Whether payload has version-modified fields
    long timestamp;            // For cross-node merge ordering
    long recentExecPathHash;   // Hash of DEF (stored but NOT used in similarity)
    int[] recentExecPath;      // Raw K=128 block IDs (currently all zeros)
    String log;                // Debug: payload class name
}
```

### Current Similarity Computation

The primary comparison uses windowed canonical trace similarity (Phase 4):
```
TraceEntry.canonicalMessageKey() → "direction|srcRole->dstRole|semanticType"
  → Trace.getCanonicalMultiset() → Map<String, Integer>
  → DiffComputeSemanticSimilarity.compute() → weighted multiset Jaccard
  → Per-window + aggregate scoring gates corpus admission
```

An optional debug metric (Phase 6, off by default):
```
TraceEntry flow partitioning by canonicalEndpointKey()
  → DiffComputeCompressedOrder → per-flow LCS similarity
  → [TRACE-DEBUG] log only, no admission effect
```

### Verified Experiment Results (Cassandra 3.11.17 → 4.1.4)

Config: `cass_diff_3.11.17_4.1.4.json` with `testingMode: 3`, `differentialExecution: true`

Trace sizes per cluster (2 nodes each):
- Old-Old: ~1300 entries, Rolling: ~1000, New-New: ~1500

Jaccard similarity (3 iterations):
- Sim[0]=0.527, Sim[1]=0.201
- Sim[0]=0.548, Sim[1]=0.161
- Sim[0]=0.477, Sim[1]=0.205

Known issues in current implementation:
- `recentExecPath` is all zeros — `Runtime.hit()` never called (DEF not working)
- `changedMessage` always false — `modifiedFields.json` not loaded in Docker containers
- Similarity only uses verb name hashcode — ignores DEF and changedMessage
- Similarity is logged but NOT connected to corpus selection (dead-end feedback)

### Manual Instrumentation Points (Cassandra)

**Cassandra 3.11.17** (`cassandra-build/cassandra-3.11.17/`):
- SEND: `OutboundTcpConnection.java:387` — `Runtime.record("SEND_"+verb, 1, payload)` before serialize
- RECV: `IncomingTcpConnection.java:216` — `Runtime.record("RECV_"+verb, 2, payload)` before receive
- Init: `CassandraDaemon.java` — `Runtime.init()` in main()

**Cassandra 4.1.4** (`cassandra-build/apache-cassandra-4.1.4-src/`):
- SEND: `OutboundConnection.java:816,988` — Two paths (EventLoop + LargeMessage)
- RECV: `InboundMessageHandler.java:431` — `ProcessMessage.run()` before `consumer.accept()`
- Init: `CassandraDaemon.java` — `Runtime.init()` in activate()

### Prebuild Directory Structure

Instrumented jars and runtime are deployed via prebuild (volume-mounted, not baked into images):
```
prebuild/cassandra/apache-cassandra-3.11.17/lib/
  ├── apache-cassandra-3.11.17.jar    # Instrumented Cassandra jar
  └── ssgFatJar.jar                   # Runtime (net.tracker + ocov.tracker)

prebuild/cassandra/apache-cassandra-4.1.4/lib/
  ├── apache-cassandra-4.1.4.jar
  └── ssgFatJar.jar
```

### configInfo Directory

Pre-computed version comparison data:
```
configInfo/apache-cassandra-3.11.17_apache-cassandra-4.1.4/
  ├── modifiedFields.json      # Classes with removed/type-changed fields
  └── modifiedEnums.json       # Enums that changed between versions
```

Generated by dinv-monitor's `ModifiedFields` tool. Format: `Map<ClassName, Set<FieldName>>`.

---

## Design Documentation

Detailed plans and design docs are in `docs/shuai/`:
- `docs/shuai/network-trace-design.md` — DEF/MSG/USE design with concrete examples
- `docs/shuai/plans/plan1-similarity-as-fuzzing-guidance.md` — Connecting similarity to corpus
- `docs/shuai/plans/plan2-automatic-instrumentation.md` — Automating network trace instrumentation
- `docs/shuai/plans/plan3-implement-use-tracking.md` — USE (post-receive path) tracking
- `docs/shuai/plans/implementation-plan-network-trace.md` — Phased implementation plan across all repos

### Known Fix: cqlsh_daemon.py

`cqlsh_daemon.py` imports `from six import StringIO` but `six` is not installed for Python 2 in Docker.
Fixed with: `try: from six import StringIO / except: from StringIO import StringIO`
Applied to both `prebuild/.../bin/cqlsh_daemon.py` and `src/.../compile-src/cqlsh_daemon.py`
