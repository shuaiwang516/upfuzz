# Differential Execution Verification Report

**Date**: 2026-02-07
**Config**: `cass_diff_config.json` (Cassandra 3.11.17 -> 4.1.4, 2 nodes, testingMode=3)

---

## 1. Cluster Verification

### 1.1 Cluster Setup

All 3 clusters launched with **correct image** `upfuzz_cassandra:apache-cassandra-3.11.17_apache-cassandra-4.1.4` and **2 nodes each** (6 containers total).

| Cluster | Executor | Direction | Type Label | Nodes Version at Start |
|---------|----------|-----------|------------|----------------------|
| Old-Old | IJKnMwtf | 0 | original | Both on 3.11.17 |
| Rolling | tB5ijQRK | 0 | original->upgraded | Both on 3.11.17 initially |
| New-New | F1Ud3S1M | 1 | upgraded | Both on 4.1.4 |

**Verified via `set_env` inside containers:**
- `IJKnMwtf` (Old-Old): `CASSANDRA_HOME="/cassandra/apache-cassandra-3.11.17"`
- `F1Ud3S1M` (New-New): `CASSANDRA_HOME="/cassandra/apache-cassandra-4.1.4"`
- `tB5ijQRK` (Rolling): Started 3.11.17, upgraded Node[0] to 4.1.4

### 1.2 Event Execution Per Cluster

**Old-Old (IJKnMwtf, thread-2):**
- Received test plan with UpgradeOp
- UpgradeOp was executed (direction=0 — this is the **Rolling** cluster, not Old-Old)

Wait, let me re-verify the thread-to-cluster mapping from the actual logs:

Looking at the event execution:
- **thread-1 (tB5ijQRK)**: Executed `[Fault] RestartFailure: Node[0]` at line 112, Node0 restart successfully (line 159). -> This is a **non-rolling** cluster (UpgradeOp replaced with RestartFailure). Since tB5ijQRK has type=`original` (direction=0), this is the **Old-Old** cluster.
- **thread-2 (IJKnMwtf)**: Executed `[UpgradeOp] Upgrade Node[0]` at line 97, then `upgrade version start` (line 130), then `upgrade failed` (line 233). -> This has the real UpgradeOp. This is the **Rolling** cluster.
- **thread-3 (F1Ud3S1M)**: Executed `[Fault] RestartFailure: Node[0]` at line 144, Node0 restart successfully (line 178). -> This is the **New-New** cluster (direction=1, type=upgraded).

**Corrected cluster identification:**

| Cluster | Executor | Thread | Events |
|---------|----------|--------|--------|
| **Old-Old** | tB5ijQRK | thread-1 | Commands -> RestartFailure (restart only, no version change) -> Commands |
| **Rolling** | IJKnMwtf | thread-2 | Commands -> UpgradeOp (3.11.17->4.1.4, failed after 300s timeout) -> Traces collected |
| **New-New** | F1Ud3S1M | thread-3 | Commands -> RestartFailure (restart only, stays on 4.1.4) -> Commands |

### 1.3 Verification Summary

- **Old-Old**: All nodes always on 3.11.17. UpgradeOp replaced with RestartFailure (restart without version change). Node restarted and reconnected successfully.
- **New-New**: All nodes always on 4.1.4. UpgradeOp replaced with RestartFailure (restart without version change). Node restarted and reconnected successfully.
- **Rolling**: Started on 3.11.17. UpgradeOp triggered real upgrade of Node[0] to 4.1.4. Upgrade failed due to `ConfigurationException: Cannot change the number of tokens from 256 to 16` (real Cassandra incompatibility). Traces still collected from both nodes.

---

## 2. Network Trace Verification

### 2.1 Trace Presence and Size

All 3 clusters produced network traces from both nodes. Traces were collected via port 62000 (ssg-runtime's `Runtime` server) and merged by timestamp across nodes.

| Cluster | Per-Node Trace Sizes | Merged Size | Time Span |
|---------|---------------------|-------------|-----------|
| Old-Old | 657 + 691 | 1364 | 135.3s |
| Rolling | 523 + 1328 | 1774 | 424.6s (includes 300s timeout) |
| New-New | 842 + 869 | 1652 | 147.3s |

### 2.2 DEF Analysis (Pre-Send Execution Path, 128 branches)

The `recentExecPath` field in each `TraceEntry` represents the **DEF** — the 128 most recent basic block IDs executed before a SEND or RECV event, captured via a ring buffer in `Runtime.snapshot()`.

**Finding: DEF is NOT active.**

All 4790 trace entries across all 3 clusters have `recentExecPath=[0, 0, 0, ..., 0]` (128 zeros).

This means `Runtime.hit(int id)` is never called by the instrumented Cassandra code. The `hit()` method records basic block IDs into a per-thread ring buffer. For DEF to work, Cassandra's bytecode must be instrumented to call `Runtime.hit()` at each basic block entry point. This instrumentation is NOT present in the current prebuild jars.

**All clusters share the same `recentExecPathHash = -8925661592318841563`** (the FNV-1a hash of 128 zeros).

### 2.3 USE Analysis (Post-Receive Execution Path)

**USE tracking is NOT YET IMPLEMENTED.** There is no instrumentation to capture the execution path after a message is received and processed. The current `TraceEntry` only records DEF (before send/recv), not a separate post-receive path.

### 2.4 Message Recording

Each network message is recorded by manually instrumented `Runtime.record()` calls in Cassandra source:

- **Cassandra 3.11.17**: `OutboundTcpConnection.java:387` (SEND), `IncomingTcpConnection.java:216` (RECV)
- **Cassandra 4.1.4**: `OutboundConnection.java:816,988` (SEND), `InboundMessageHandler.java:431` (RECV)

Each `TraceEntry` contains:
- `id`: 1=SEND, 2=RECV
- `methodName`: e.g., `SEND_GOSSIP_DIGEST_SYN`, `RECV_MUTATION`
- `hashcode`: `methodName.hashCode()` (Java String hash)
- `changedMessage`: Whether payload contains version-modified fields (always false — see Section 2.5)
- `timestamp`: For cross-node merge ordering
- `recentExecPath`: 128 block IDs (all zeros — DEF not active)

### 2.5 changedMessage Analysis

**Finding: changedMessage is always false across all clusters (0/4790 entries).**

The `changedMessage` field is set by `ObjectGraphTraverser.examineChangedMessage()` which checks if the message payload contains classes listed in `modifiedFields.json`. This file is not loaded inside the Docker containers (it exists at `configInfo/` on the host but is not mounted into containers).

---

## 3. Jaccard Similarity Root Cause Analysis

### 3.1 How Similarity Is Computed

1. Each cluster's merged trace produces a **hashcode sequence**: `[h1, h2, h3, ...]` where each `hi = methodName.hashCode()`
2. **2-grams** are generated: `["h1-h2", "h2-h3", "h3-h4", ...]`
3. **Jaccard similarity** = |intersection of 2-gram sets| / |union of 2-gram sets|

Sim[0] = Jaccard(Old-Old, Rolling) = **0.50**
Sim[1] = Jaccard(Rolling, New-New) = **0.156**

### 3.2 Message Type Differences Between Versions

The **Old-Old (3.11.17)** and **New-New (4.1.4)** clusters use **completely different message verb names** for the same protocol operations:

| Operation | 3.11.17 (Old-Old) | 4.1.4 (New-New) |
|-----------|-------------------|-----------------|
| Echo/Ping | `SEND_ECHO`/`RECV_ECHO` | `SEND_ECHO_REQ`/`RECV_ECHO_REQ`/`SEND_ECHO_RSP`/`RECV_ECHO_RSP` + `SEND_PING_REQ`/`RECV_PING_REQ`/`SEND_PING_RSP`/`RECV_PING_RSP` |
| Schema | `SEND_DEFINITIONS_UPDATE`/`RECV_DEFINITIONS_UPDATE` | `SEND_SCHEMA_PUSH_REQ`/`RECV_SCHEMA_PUSH_REQ`/`SEND_SCHEMA_PULL_REQ`/etc. |
| Read | `SEND_READ`/`RECV_READ` | `SEND_READ_REQ`/`RECV_READ_REQ`/`SEND_READ_RSP`/`RECV_READ_RSP` |
| Migration | `SEND_MIGRATION_REQUEST`/`RECV_MIGRATION_REQUEST` | (not seen in New-New trace) |
| Response | `SEND_REQUEST_RESPONSE`/`RECV_REQUEST_RESPONSE`/`SEND_INTERNAL_RESPONSE`/`RECV_INTERNAL_RESPONSE` | (not used in 4.1.4 — each verb has its own RSP) |

**Common to all 3 clusters** (unchanged between versions):
- `SEND_GOSSIP_DIGEST_SYN` / `RECV_GOSSIP_DIGEST_SYN`
- `SEND_GOSSIP_DIGEST_ACK` / `RECV_GOSSIP_DIGEST_ACK`
- `SEND_GOSSIP_DIGEST_ACK2` / `RECV_GOSSIP_DIGEST_ACK2`
- `RECV_GOSSIP_SHUTDOWN`

### 3.3 Root Cause: Sim[0] = 0.50 (Old-Old vs Rolling)

| Metric | Value |
|--------|-------|
| Unique 2-grams in Old-Old | 51 |
| Unique 2-grams in Rolling | 72 |
| Shared | 41 |
| Only in Old-Old | 10 |
| Only in Rolling | 31 |
| Jaccard | 41/82 = **0.50** |

**Why they differ (31 2-grams unique to Rolling):**

The Rolling cluster has **more diverse message patterns** because:
1. **Node[0] attempted upgrade** (3.11.17 -> 4.1.4), which triggered repeated crash-loops. During these restarts, the surviving Node[1] (still on 3.11.17) sends many `SEND_GOSSIP_DIGEST_SYN` without receiving responses, creating unique patterns like:
   - `SEND_GOSSIP_DIGEST_SYN -> SEND_GOSSIP_DIGEST_SYN` (397 occurrences vs 62 in Old-Old) — rapid-fire gossip when peer is down
   - `SEND_REQUEST_RESPONSE -> SEND_REQUEST_RESPONSE` (5 occurrences, absent in Old-Old) — responses queuing up
   - `SEND_ECHO -> SEND_REQUEST_RESPONSE` transitions (many variations) — the crashed node's restart attempts

2. **Longer trace duration** (424.6s vs 135.3s) — the Rolling cluster ran much longer waiting for the upgrade timeout, producing more varied gossip patterns.

3. **The 10 2-grams unique to Old-Old** include patterns around `RECV_GOSSIP_SHUTDOWN` and `SEND_READ` which occurred in Old-Old's clean shutdown but were disrupted in Rolling.

### 3.4 Root Cause: Sim[1] = 0.156 (Rolling vs New-New)

| Metric | Value |
|--------|-------|
| Unique 2-grams in Rolling | 72 |
| Unique 2-grams in New-New | 106 |
| Shared | 24 |
| Only in Rolling | 48 |
| Only in New-New | 82 |
| Jaccard | 24/154 = **0.156** |

**Why they differ dramatically:**

1. **Version-specific message verbs** — New-New (4.1.4) uses completely different message names than Rolling (which mixes 3.11.17 messages). The 4.1.4 protocol introduces:
   - `ECHO_REQ`/`ECHO_RSP` (replacing `ECHO`)
   - `PING_REQ`/`PING_RSP` (new protocol)
   - `SCHEMA_PUSH_REQ`/`SCHEMA_PULL_REQ`/`SCHEMA_PULL_RSP` (replacing `DEFINITIONS_UPDATE` and `MIGRATION_REQUEST`)
   - `READ_REQ`/`READ_RSP` (replacing `READ` + `REQUEST_RESPONSE`)

   These produce **82 unique 2-grams** that don't exist in Rolling at all.

2. **Rolling has crash-loop patterns** — The 48 2-grams unique to Rolling come from the upgrade failure and resulting gossip disruption (rapid `SEND_GOSSIP_DIGEST_SYN` sequences, `REQUEST_RESPONSE` batching).

3. **Only 24 shared 2-grams** — These are the Gossip protocol transitions (`SEND_GOSSIP_DIGEST_SYN -> RECV_GOSSIP_DIGEST_SYN -> SEND_GOSSIP_DIGEST_ACK -> ...`) which are common across all Cassandra versions.

### 3.5 Summary of Similarity Root Causes

```
Sim[0] = 0.50 (Old-Old vs Rolling)
  Cause: Rolling has extended trace from upgrade timeout + crash-loop gossip patterns
  Both are 3.11.17 protocol, so verbs match. Structural patterns differ.

Sim[1] = 0.156 (Rolling vs New-New)
  Cause: Completely different message verb names between 3.11.17 and 4.1.4
  + Rolling has crash-loop disruption patterns
  Result: Very few 2-grams in common (only basic Gossip handshake).
```

---

## 4. Known Issues

| Issue | Status | Impact |
|-------|--------|--------|
| DEF (recentExecPath) all zeros | NOT WORKING | `Runtime.hit()` never called — need bytecode instrumentation |
| USE (post-receive path) | NOT IMPLEMENTED | No execution path tracking after message processing |
| changedMessage always false | NOT WORKING | `modifiedFields.json` not mounted in containers |
| Rolling upgrade crashes (num_tokens 256->16) | REAL BUG | Cassandra 3.11.17->4.1.4 config incompatibility |
| Jaccard uses only methodName.hashCode() | BY DESIGN | DEF and changedMessage could improve sensitivity when working |

---

## 5. Artifacts

- Server log: `logs/upfuzz_server.log` (4824 lines, includes all trace entries)
- Client log: `logs/upfuzz_client_1.log`
- Failure reports: `failure/failure_0/` (event_crash, errorLog)
- Analysis script: `analyze_traces.py`
