# Network Trace Design: DEF, MSG, USE

## Overview

Upgrade bugs happen when **version A sends something that version B misinterprets (or vice versa)**. The fuzzer needs to explore diverse network interactions to find these cases. Each component of the (DEF, MSG, USE) tuple serves a different role in guiding that exploration.

The full tuple creates a **fingerprint for each network interaction**. The Jaccard similarity on these fingerprints tells the fuzzer how different the network behavior is across the 3 clusters (Old-Old, Rolling, New-New). Divergence suggests version-incompatible behavior.

---

## DEF — "Why was this message sent?"

DEF captures the **causal context** that triggered a message. Its purpose is to distinguish between different reasons the same message type gets sent, so the fuzzer can explore each reason separately.

### What DEF should track

| # | Track | What it captures | Why it matters for fuzzing |
|---|-------|-----------------|---------------------------|
| 1 | **Triggering operation** | Which user command / internal operation caused this message | Different operations exercise different serialization paths |
| 2 | **Branch decisions before send** | Which if/else branches were taken on the way to the send point | Different branch paths may serialize data differently |
| 3 | **Caller chain** | Which methods called into the send path | Distinguishes direct send vs. callback vs. retry |
| 4 | **Sender version state** | Is this node pre-upgrade, mid-upgrade, or post-upgrade | Same code path may behave differently depending on upgrade state |

### Concrete Cassandra examples

**Example 1 — Same verb, different triggers:**

```
Test A: INSERT INTO users (id, name) VALUES (1, 'Alice');
  → Node 1 sends MUTATION to Node 2 (replica write)
  DEF = [CqlHandler.process → StorageProxy.mutate → MessagingService.send]

Test B: nodetool repair
  → Node 1 sends MUTATION to Node 2 (repair stream)
  DEF = [RepairSession.run → StreamPlan.execute → MessagingService.send]
```

Both produce `SEND_MUTATION`, but DEF distinguishes them. The repair path may serialize additional metadata fields that the INSERT path doesn't. If version B changed how repair metadata is serialized, only Test B finds the bug.

**Example 2 — Same operation, different branch:**

```
Test C: INSERT with TTL (time-to-live)
  → Serializer checks: if (hasTTL) { writeTTL(ttl); }
  DEF = [..., branch_hasTTL=true, ...]

Test D: INSERT without TTL
  → Serializer skips TTL serialization
  DEF = [..., branch_hasTTL=false, ...]
```

If version B added a new TTL format, only Test C (hasTTL=true branch) will trigger the incompatible serialization.

### Implementation approach

The current ring buffer of 128 recent block IDs is a reasonable design. The key question is granularity:

- **Block IDs from boundary branches** (vasco's `allBoundaryBranchLocations.json`): Captures serialization-relevant decisions. Most efficient — directly tracks the decisions that affect what bytes go on the wire.
- **Block IDs from all branches**: Captures general control flow. More discriminating but noisier.
- **Method entry IDs only**: Captures the call chain. Coarser but very cheap.

**Recommendation:** Start with boundary branches from vasco's existing analysis. These are the decisions that directly control serialization format — exactly what causes upgrade bugs.

### Current status

- Ring buffer infrastructure: **Implemented** (`Runtime.hit()`, `snapshot()`, K=128)
- Instrumentation of `hit()` into target code: **NOT done** — `recentExecPath` is all zeros
- Can reuse: `allBoundaryBranchLocations.json` from vasco, `InstBoundaryBranchPoint` from dinv-monitor

---

## MSG — "What was sent?"

MSG captures **the message itself**. This is the most directly relevant component because upgrade bugs are fundamentally about message format incompatibility.

### What MSG should track

| # | Track | What it captures | Why it matters for fuzzing |
|---|-------|-----------------|---------------------------|
| 1 | **Message verb** | MUTATION, GOSSIP_DIGEST_SYN, READ, etc. | Different verbs = different serialization code paths |
| 2 | **Payload class types** | Which Java classes are in the payload object graph | Identifies which data structures are being serialized |
| 3 | **Changed-class flag** | Whether payload contains fields from classes modified between versions | Directly identifies version-sensitive messages |
| 4 | **Serialization version** | The `MessagingService.VERSION` used for this message | Different versions may use different wire formats |
| 5 | **Field presence bitmap** | Which optional fields are present in the payload | Missing/added fields are a primary source of upgrade bugs |
| 6 | **Payload size** | Byte count of serialized message | Size differences between clusters indicate format changes |

### Concrete Cassandra examples

**Example 3 — Changed message detection:**

```
Test E: CREATE TABLE with compression options
  → Node 1 sends SCHEMA_CHANGE to Node 2
  → Payload contains: TableParams (includes CompressionParams)
  → CompressionParams class was modified between 3.11 → 4.1 (new fields added)
  → changedMessage = TRUE ← This message is high priority for fuzzing!
```

```
Test F: INSERT INTO simple_table (id) VALUES (1)
  → Node 1 sends MUTATION to Node 2
  → Payload contains: Mutation → PartitionUpdate (unchanged between versions)
  → changedMessage = FALSE ← Lower priority
```

The fuzzer should heavily favor Test E because it exercises a version-modified data structure.

**Example 4 — Field presence matters:**

```
Cassandra 3.11: GossipDigestSyn serializes {clusterName, partitioner, digestList}
Cassandra 4.1:  GossipDigestSyn serializes {clusterName, partitioner, digestList, tableSchemasVersion}
                                                                                   ^^^^^^^^^^^^^^^^
                                                                                   NEW FIELD
```

If the fuzzer tracks which fields are present, it can detect that 4.1 sends an extra field that 3.11 doesn't expect. This is exactly the type of incompatibility we want to find.

### Current status

| Tracked | Status | Impact |
|---------|--------|--------|
| Message verb | ✅ `SEND_MUTATION`, `RECV_GOSSIP_DIGEST_SYN` | Good — distinguishes message types |
| Payload class types | ✅ `ObjectGraphTraverser` walks payload | Good — finds types in object graph |
| Changed-class flag | ⚠️ Infrastructure exists but `modifiedFields.json` appears empty | **Critical gap** — this is the most valuable signal |
| Serialization version | ❌ Not tracked | Could distinguish version-negotiated formats |
| Field presence bitmap | ❌ Not tracked | Would catch added/removed field bugs |
| Payload size | ❌ Not tracked | Cheap proxy for "something changed" |

---

## USE — "What happened after receiving?"

USE captures **how the receiver processed the message**. This is where bugs actually **manifest** — the receiver crashes, misinterprets data, takes a wrong handler path, or silently corrupts state.

### What USE should track

| # | Track | What it captures | Why it matters for fuzzing |
|---|-------|-----------------|---------------------------|
| 1 | **Handler execution path** | Which code blocks execute in the verb handler after deserialization | Different handler paths = different interpretation of the message |
| 2 | **Deserialization success/failure** | Did deserialization complete without errors | Catches format incompatibility crashes |
| 3 | **Handler branch decisions** | Which branches the handler takes (especially version checks) | Detects version-conditional behavior |
| 4 | **Triggered follow-up messages** | Did processing this message cause the receiver to send more messages | Captures cascading effects (gossip → ack → ack2) |
| 5 | **State mutation** | What the receiver wrote to memtable/commitlog/etc. after processing | Detects silent data corruption |

### Concrete Cassandra examples

**Example 5 — Handler path divergence (the ideal bug-finding scenario):**

```
Old node sends MUTATION with field X serialized in old format
                    ↓
Old-Old cluster:    RECV handler reads field X with old deserializer     → success
Rolling cluster:    RECV handler reads field X with NEW deserializer    → reads wrong value!
New-New cluster:    never sends old format, so this path never executes

DEF: [same — both senders take same path]
MSG: [same — same bytes on the wire]
USE: [DIFFERENT — old handler vs new handler produce different execution paths]
     ^^^^^^^^
     THIS IS THE BUG SIGNAL
```

The USE divergence between Old-Old and Rolling is the direct signal. The old-format message gets misinterpreted by the new handler.

**Example 6 — Version-conditional handler:**

```
Cassandra 4.1 InboundMessageHandler.ProcessMessage.run():
  if (message.version() < VERSION_40) {
      // Legacy path: skip new validation
      USE branch = [legacy_path]
  } else {
      // New path: validate new field
      USE branch = [new_validation_path]
  }
```

During rolling upgrade, the new node receives messages from the old node. It takes the `legacy_path` branch. If the legacy path has a bug (e.g., doesn't properly convert old format), this is only discoverable by tracking USE.

**Example 7 — Follow-up message cascade:**

```
Test G: Node 1 (old) sends GOSSIP_DIGEST_SYN to Node 2 (new)
  → Node 2 receives and processes
  → Node 2 replies with GOSSIP_DIGEST_ACK (containing endpoint states)
  → Node 1 receives ACK
  → Node 1 sends GOSSIP_DIGEST_ACK2

If version B changed the endpoint state format in ACK:
  USE of GOSSIP_DIGEST_SYN → triggers sending ACK with new format
  USE of ACK on old node → fails to parse new endpoint state format
                           ^^^^^^^^
                           BUG: Old node can't understand new ACK format
```

Tracking USE shows that processing SYN triggers a follow-up ACK with different content. The bug is in the cascade, not in the initial message.

### Current status

- USE tracking: **NOT implemented** at all
- No `recordPost()` or handler execution path capture
- Challenge: Cassandra 4.x dispatches handlers asynchronously (verb handler runs on a different thread than the receive path)

---

## How (DEF, MSG, USE) Combine to Guide Fuzzing

### The key comparison for bug-finding

```
                    Old-Old          Rolling          New-New
                    (3.11↔3.11)      (3.11↔4.1)       (4.1↔4.1)

  DEF:              path_A           path_A           path_A'
                    (same trigger)   (same trigger)   (may differ slightly)

  MSG:              MUTATION_v1      MUTATION_v1      MUTATION_v2
                    (old format)     (old→new)        (new format)

  USE:              handler_old      handler_new!     handler_new
                    (reads v1 ok)    (reads v1 ???)   (reads v2 ok)
                                     ^^^^^^^^^^^^
                                     MISMATCH = potential bug
```

The most informative comparison is **Old-Old vs Rolling** (Sim[0]):
- DEF similar + MSG similar + **USE diverges** → the new version handles the old message differently → likely bug
- **DEF diverges** → the operation itself triggers different messages between versions → interesting for exploration
- **MSG diverges** → the serialization format changed → directly version-sensitive

### What should go into the hashcode for similarity

Currently `TraceEntry.hashcode = methodName.hashCode()` — only MSG verb. The similarity is based purely on message ordering patterns.

A richer hashcode should combine:

```java
hashcode = hash(
    methodName,           // MSG: verb type (SEND_MUTATION, RECV_GOSSIP_DIGEST_SYN)
    recentExecPathHash,   // DEF: execution path fingerprint
    changedMessage,       // MSG: whether payload has version-modified fields
    postExecPathHash      // USE: handler execution path (once implemented)
);
```

This way, the Jaccard similarity captures divergence across **all three dimensions**, not just message ordering.

---

## Implementation Priority

What gives the most bug-finding value per engineering effort:

| Priority | Component | What to do | Why |
|----------|-----------|-----------|-----|
| **1** | MSG: `changedMessage` | Populate `modifiedFields.json` properly | Directly flags version-sensitive messages. Tests producing `changedMessage=true` should be top priority in corpus. Zero additional instrumentation needed — just need the JSON file. |
| **2** | DEF: boundary branches | Add `hit()` at vasco's `allBoundaryBranchLocations` | Distinguishes serialization-relevant code paths. Reuses existing vasco analysis. Makes DEF non-zero. |
| **3** | USE: handler path | Add `recordPost()` after verb handler dispatch | Captures receiver behavior divergence — the direct bug signal. Most impactful but requires identifying handler dispatch points per system. |
| **4** | MSG: payload size | Track `message.serializedSize()` | Cheap proxy for "format changed." Size difference between clusters = strong signal. |
| **5** | Richer hashcode | Combine DEF+MSG+USE into TraceEntry.hashCode() | Makes Jaccard similarity sensitive to all three dimensions simultaneously. |

---

## Verified Experiment Results (Cassandra 3.11.17 → 4.1.4)

From actual differential execution with 3 iterations:

**Trace sizes per cluster (entries across 2 nodes):**
- Old-Old: 643 + 676 = ~1300
- Rolling: 459 + 561 = ~1000
- New-New: 780 + 712 = ~1500

**Jaccard similarity:**
- Iter 1: Sim[0]=0.527 (Old-Old vs Rolling), Sim[1]=0.201 (Rolling vs New-New)
- Iter 2: Sim[0]=0.548, Sim[1]=0.161
- Iter 3: Sim[0]=0.477, Sim[1]=0.205

**Observed message types:**
- GOSSIP_DIGEST_SYN, GOSSIP_DIGEST_ACK, GOSSIP_DIGEST_ACK2
- ECHO, REQUEST_RESPONSE

**Observations:**
- `recentExecPath` is all zeros (DEF not working — `hit()` never called)
- `changedMessage` is always false (modifiedFields.json not populated)
- `log` field occasionally contains payload type (e.g., `org.apache.cassandra.gms.GossipDigestSyn`)
- Similarity is currently based ONLY on message verb ordering patterns (2-grams of verb hashcodes)

---

## Code Locations

| Component | File | Key Lines |
|-----------|------|-----------|
| Ring buffer (DEF) | `ssg-runtime/.../net/tracker/Runtime.java` | `hit()`:69-74, `snapshot()`:77-86 |
| Message recording | `ssg-runtime/.../net/tracker/Runtime.java` | `record()`:102-113 |
| Trace data structure | `ssg-runtime/.../net/tracker/Trace.java` | `record()`, `getCanonicalMultiset()`, `getCanonicalKeysForDiff()` |
| Trace entry | `ssg-runtime/.../net/tracker/TraceEntry.java` | Fields: id, methodName, eventType, nodeRole, peerRole, messageType, canonicalMessageKey() |
| Changed message detection | `ssg-runtime/.../net/tracker/Trace.java` | `examineChangedMessage()` |
| Canonical similarity | `ssg-runtime/.../net/tracker/diff/DiffComputeSemanticSimilarity.java` | Weighted multiset Jaccard on canonical keys |
| Compressed order (debug) | `ssg-runtime/.../net/tracker/diff/DiffComputeCompressedOrder.java` | Per-flow LCS similarity |
| Similarity computation trigger | `upfuzz/.../FuzzingServer.java` | Windowed canonical scoring + tri-diff |
| Corpus (feedback not connected) | `upfuzz/.../InterestingTestsCorpus.java` | 6-tier queue, `addSeed()` |
| Vasco boundary branches | `allBoundaryBranchLocations.json` | Output of vasco static analysis |
| dinv-monitor branch instrumenter | `dinv-monitor/.../InstBoundaryBranchPoint.java` | `recurProcess()`:76-113 |
| Cassandra 3.11 SEND | `cassandra-build/cassandra-3.11.17/.../OutboundTcpConnection.java` | Line 387 |
| Cassandra 3.11 RECV | `cassandra-build/cassandra-3.11.17/.../IncomingTcpConnection.java` | Line 216 |
| Cassandra 4.1 SEND | `cassandra-build/apache-cassandra-4.1.4-src/.../OutboundConnection.java` | Lines 816, 988 |
| Cassandra 4.1 RECV | `cassandra-build/apache-cassandra-4.1.4-src/.../InboundMessageHandler.java` | Line 431 |
