# Network Trace Guided Rolling Upgrade Fuzzing: Implementation Status

This document provides a detailed breakdown of what is implemented and what is missing for the network trace guided rolling upgrade fuzzing feature.

## Overview

The network trace guided fuzzing uses **<DEF, MSG, USE>** tuples to track network communications during rolling upgrades:
- **DEF**: Execution path (branch IDs) before message send
- **MSG**: Message content and type information
- **USE**: Execution path after message receive

By comparing traces across three cluster configurations (Old-Old, Rolling Upgrade, New-New), we can detect behavioral differences that may indicate compatibility bugs.

---

## Implementation Status Summary

| Category | Component | Status | Priority |
|----------|-----------|--------|----------|
| **DEF** | Ring buffer for branch recording | Done | - |
| **DEF** | Branch hit recording (`hit()`) | Done | - |
| **DEF** | Snapshot mechanism | Done | - |
| **DEF** | Automatic `hit()` injection | **Missing** | High |
| **MSG** | Changed type detection | Done | - |
| **MSG** | Object graph traversal | Done | - |
| **MSG** | Payload type extraction | Done | - |
| **MSG** | Actual content capture | **Missing** | Medium |
| **MSG** | Network send point detection (vasco) | Done | - |
| **MSG** | Bytecode instrumentation (vasco) | Done (needs adaptation) | - |
| **MSG** | `Runtime.record()` injection | **Missing** | High |
| **USE** | Receive-side path tracking | **Missing** | High |
| **Trace** | Collection from Docker nodes | Done | - |
| **Trace** | Timestamp-based merging | Done | - |
| **Comparison** | Jaccard similarity | Done | - |
| **Comparison** | Edit distance | Done | - |
| **Comparison** | Anomaly message detection | **Missing** | Low |
| **Feedback** | Similarity computation | Done | - |
| **Feedback** | Corpus selection integration | **Missing** | **Critical** |
| **Feedback** | Test prioritization | **Missing** | **Critical** |
| **Infra** | Noise filtering | **Missing** | Medium |
| **Infra** | Configuration flags | Done | - |

---

## Key Finding: Vasco Has Automatic Network Instrumentation

**Important Discovery:** The `vasco` repository contains infrastructure for **automatic network message instrumentation** that can identify and instrument all `OutputStream.write*()` calls. However, it's currently configured for format coverage, not network trace.

### What Exists in Vasco

| Component | Location | Status |
|-----------|----------|--------|
| `OutputStreamInstrumenterOri.java` | `vasco/src/main/java/vasco/soot/instrumenter/` | Instruments ALL `write*()` calls |
| `internalTransformOri()` method | `OutputStreamInstrumenter.java:311-431` | Alternative transform for message recording |
| `Runtime.writeOutputStreamRecord()` | `vasco/.../instrumenter/Runtime.java:48-69` | Records message data, type, context |
| `TraceWriter` | `vasco/.../instrumenter/TraceWriter.java` | Writes traces to file |
| Network class detection | `OutputStreamInstrumenter.java:32-39` | Identifies all OutputStream/DataOutput subclasses |

### How Vasco's Instrumenter Works

```java
// OutputStreamInstrumenterOri automatically:
// 1. Finds all OutputStream and DataOutput subclasses
SootClass outputStream = Scene.v().getSootClass("java.io.OutputStream");
SootClass dataoutput = Scene.v().getSootClass("java.io.DataOutput");
allOutputClassSigs = Utils.getChildrenOfIncluding(startClasses);

// 2. Intercepts write*() method calls
if (allOutputClassSigs.contains(m.getDeclaringClass().getName())) {
    if (m.getName().startsWith("write")) {
        // 3. Injects recording call
        Runtime.writeOutputStreamRecord(datatype, contextID, data);
    }
}
```

### The Gap: Wrong Runtime Class

| Current State | What's Needed |
|---------------|---------------|
| Injects `ocov.tracker.Runtime.update()` | Should inject `net.tracker.Runtime.record()` |
| Used for format coverage | Need for network trace |
| Records object state | Need to record message + execution path |

---

## Detailed Implementation Status

### 1. DEF (Definition - Execution Path Before Send)

#### What's Done

| Component | Location | Description |
|-----------|----------|-------------|
| Ring Buffer | `ssg-runtime/.../net/tracker/Runtime.java:32-35` | ThreadLocal ring buffer storing last 128 branch IDs |
| Branch Recording | `Runtime.java:68-74` (`hit()` method) | Records branch ID into circular buffer on each branch execution |
| Snapshot | `Runtime.java:76-86` (`snapshot()` method) | Captures the last 128 branches before message send |
| Thread Safety | `Runtime.java:32-34` | Uses `ThreadLocal` for per-thread isolation |

**Code Example:**
```java
// Ring buffer setup
private static final int K = 128;
private static final ThreadLocal<int[]> buf = ThreadLocal.withInitial(() -> new int[K]);
private static final ThreadLocal<Integer> idx = ThreadLocal.withInitial(() -> 0);

// Branch recording
public static void hit(int id) {
    int[] b = buf.get();
    int i = idx.get();
    b[i & (K - 1)] = id;  // Circular buffer write
    idx.set(i + 1);
}
```

#### What's Missing

| Component | Priority | Description |
|-----------|----------|-------------|
| **Automatic `hit()` injection** | High | No bytecode instrumentation to inject `hit()` at every branch. Could leverage JaCoCo's instrumentation or create similar mechanism. |

---

### 2. MSG (Message - Content and Type Detection)

#### What's Done

| Component | Location | Description |
|-----------|----------|-------------|
| Changed Type Detection | `ssg-runtime/.../Trace.java:42-90` | `examineChangedMessage()` checks if message contains changed types |
| Object Graph Traversal | `ssg-runtime/.../ObjectGraphTraverser.java:23-103` | Recursively extracts all types from message objects via reflection |
| Changed Classes Loading | `ssg-runtime/.../Runtime.java:37-65` | Loads `modifiedFields.json` to identify changed classes between versions |
| Payload Type Extraction | `ssg-runtime/.../Trace.java:51-79` | Extracts and logs the message payload type |
| **Network Send Point Detection** | `vasco/.../SerializationAnalysis.java` | Identifies all `OutputStream.write*()` calls via static analysis |
| **Bytecode Instrumentation** | `vasco/.../OutputStreamInstrumenter.java` | Soot-based bytecode transformation at write points |
| **Write Point Recording** | `vasco/.../Runtime.java:48-69` | `writeOutputStreamRecord()` logs message data |

#### What's Missing

| Component | Priority | Description |
|-----------|----------|-------------|
| **Connect to `net.tracker.Runtime.record()`** | **High** | Vasco instruments write points but calls wrong Runtime method |
| **Actual Content Capture** | Medium | Only type information is captured, not actual field values |

---

### 3. USE (Usage - Execution Path After Receive)

#### What's Done

- **Nothing** - USE is not implemented

#### What's Missing

| Component | Priority | Description |
|-----------|----------|-------------|
| **Receive-side Instrumentation** | **High** | No hooks for message receive events |
| **Post-receive Path Recording** | **High** | No snapshot of execution after message is processed |
| **DEF-USE Correlation** | **High** | Cannot correlate sender's construction path with receiver's processing path |

**Implementation Note:** Could extend vasco to also instrument `InputStream.read*()` and `DataInput.read*()` calls using the same pattern as the send-side instrumentation.

---

### 4. Trace Collection and Comparison

#### What's Done

| Component | Location | Description |
|-----------|----------|-------------|
| Docker Collection | `upfuzz/.../Docker.java:72-87` | Connects to port 62000 to collect traces from nodes |
| Executor Integration | `upfuzz/.../Executor.java:140-146` | `updateTrace()` collects traces from all cluster nodes |
| Timestamp Merging | `ssg-runtime/.../Trace.java` | `mergeBasedOnTimestamp()` combines traces from multiple nodes |
| Jaccard Similarity | `ssg-runtime/.../diff/DiffComputeJaccardSimilarity.java` | Computes similarity using n-grams (N=2) |
| Edit Distance | `ssg-runtime/.../diff/DiffComputeEditDistance.java` | Computes Levenshtein distance between traces |

#### What's Missing

| Component | Priority | Description |
|-----------|----------|-------------|
| **Anomaly Detection** | Low | `DiffComputeAnomalyMessage.java` throws `NotImplementedException` |
| **Noise Filtering** | Medium | No filtering of irrelevant trace entries (e.g., heartbeats, gossip) |

---

### 5. Fuzzing Feedback Integration

#### What's Done

| Component | Location | Description |
|-----------|----------|-------------|
| Similarity Computation | `FuzzingServer.java:1504-1520` | Computes Jaccard/EditDistance between 3 cluster traces |
| Logging | `FuzzingServer.java:1518-1519` | Logs similarity values to console |
| Config Flags | `Config.java:319-326` | `useTrace`, `useJaccardSimilarity`, `useEditDistance` flags |

#### What's Missing (CRITICAL)

| Component | Priority | Description |
|-----------|----------|-------------|
| **Corpus Selection** | **Critical** | Trace similarity NOT used to decide if test is "interesting" |
| **Test Prioritization** | **Critical** | Low similarity tests NOT prioritized for mutation |
| **Threshold-based Flagging** | **Critical** | No threshold to flag tests with significantly different traces |

**Current Data Flow (Broken Feedback Loop):**
```
Trace Collected → Similarity Computed → Logged → ❌ NOTHING HAPPENS
                                                   ↓
                                        Test prioritization unchanged
                                        Corpus selection unchanged
```

**Required Data Flow:**
```
Trace Collected → Similarity Computed → Check threshold
                                              ↓
                              ┌───────────────┴───────────────┐
                              ↓                               ↓
                    similarity < threshold           similarity >= threshold
                              ↓                               ↓
                    Add to corpus with              Lower priority
                    HIGH priority                   Normal handling
                    Flag for investigation
```

---

## Configuration Options

All trace-related options in `Config.java`:

```java
// Network Trace Coverage (lines 316-326)
public boolean useTrace = false;              // Master switch for trace collection
public boolean differentialExecution = false; // Enable 3-cluster differential testing
public boolean printTrace = false;            // Debug: print trace entries
public boolean useEditDistance = false;       // Use edit distance metric
public boolean useJaccardSimilarity = true;   // Use Jaccard similarity metric
```

**Note:** All disabled by default.

---

## Files Reference

### ssg-runtime (Trace Runtime Library)

| File | Purpose |
|------|---------|
| `src/main/java/org/zlab/net/tracker/Runtime.java` | Branch recording, ring buffer, snapshot, `record()` method |
| `src/main/java/org/zlab/net/tracker/Trace.java` | Trace storage, changed message detection |
| `src/main/java/org/zlab/net/tracker/TraceEntry.java` | Single trace entry data structure |
| `src/main/java/org/zlab/net/tracker/ObjectGraphTraverser.java` | Object type extraction via reflection |
| `src/main/java/org/zlab/net/tracker/diff/DiffComputeJaccardSimilarity.java` | Jaccard similarity computation |
| `src/main/java/org/zlab/net/tracker/diff/DiffComputeEditDistance.java` | Edit distance computation |

### vasco (Static Analysis & Instrumentation)

| File | Purpose |
|------|---------|
| `src/main/java/vasco/soot/instrumenter/OutputStreamInstrumenter.java` | Bytecode instrumentation for write points |
| `src/main/java/vasco/soot/instrumenter/OutputStreamInstrumenterOri.java` | Original instrumenter that intercepts ALL write calls |
| `src/main/java/vasco/soot/instrumenter/Runtime.java` | `writeOutputStreamRecord()` for message logging |
| `src/main/java/vasco/soot/dataflow/SerializationAnalysis.java` | Static analysis to find serialization points |
| `src/main/java/vasco/soot/dataflow/Alg1.java` | Main analysis entry point, generates `writePoints_alg1.json` |

### upfuzz (Fuzzing Engine Integration)

| File | Purpose |
|------|---------|
| `src/main/java/org/zlab/upfuzz/fuzzingengine/Config.java:316-326` | Trace configuration options |
| `src/main/java/org/zlab/upfuzz/fuzzingengine/executor/Executor.java:140-146` | Trace collection orchestration |
| `src/main/java/org/zlab/upfuzz/docker/Docker.java:72-87` | Docker trace collection |
| `src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java:1445-1521` | Trace analysis and similarity computation |

---

## Implementation Roadmap

### Phase 1: Connect Existing Pieces (Highest Priority)

**Goal:** Make vasco's instrumentation call `net.tracker.Runtime.record()` instead of format coverage methods.

1. **Modify `OutputStreamInstrumenter.java`**
   - Change `internalTransform()` to call `net.tracker.Runtime.record(name, id, messageObject)`
   - Or enable `internalTransformOri()` and redirect to correct Runtime

2. **Add message object passing**
   - Current: `writeOutputStreamRecord(datatype, contextID, data)` - only logs string representation
   - Needed: `Runtime.record(name, id, messageObject)` - passes actual object for type examination

3. **Implement feedback loop in `FuzzingServer.java`**
   - After line 1520, add corpus selection logic based on similarity threshold
   - Prioritize tests with low similarity for mutation

### Phase 2: DEF Branch Recording

**Goal:** Inject `Runtime.hit()` at every branch to populate the ring buffer.

**Options:**
1. **Leverage JaCoCo** - JaCoCo already instruments branches; could extend to also call `hit()`
2. **Extend vasco** - Add branch instrumentation alongside write point instrumentation
3. **ASM-based agent** - Create Java agent that instruments branches at class load time

### Phase 3: USE (Receive-side) Implementation

**Goal:** Track execution path after message receive.

1. **Extend vasco to find receive points**
   - Identify `InputStream.read*()`, `DataInput.read*()`, `ObjectInputStream.readObject()`
   - Similar pattern to send-side detection

2. **Instrument receive points**
   - Call `Runtime.recordReceive(name, id)` after message is processed
   - Capture post-receive execution path

### Phase 4: Enhancements

1. **Noise filtering** - Filter heartbeat/gossip messages
2. **Content capture** - Serialize actual field values
3. **Anomaly detection** - Implement `DiffComputeAnomalyMessage`

---

## Summary

| Category | Status | Action Needed |
|----------|--------|---------------|
| DEF (ring buffer, snapshot) | **Complete** | None |
| DEF (branch `hit()` injection) | **Not Done** | Need bytecode instrumentation |
| MSG (type detection) | **Complete** | None |
| MSG (send point detection) | **Complete in vasco** | Need to connect to `net.tracker.Runtime` |
| MSG (bytecode instrumentation) | **Complete in vasco** | Need to change target Runtime class |
| USE (receive-side path) | **Not Started** | Extend vasco for receive points |
| Trace Collection | **Complete** | None |
| Trace Comparison | **Complete** | None |
| Feedback Loop | **Not Connected** | **Critical: Must implement** |

**Key Insight:** The infrastructure largely exists across the repositories - the main work is **connecting the pieces**:
1. Vasco CAN find and instrument network send points
2. ssg-runtime HAS the trace recording and comparison logic
3. upfuzz HAS the 3-cluster differential execution framework
4. **Missing:** The instrumentation calls the wrong Runtime method, and similarity scores don't affect fuzzing decisions
