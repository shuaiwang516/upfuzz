# Plan 3: Implement USE (Receive-Side Execution Path Tracking)

## Current State

### The DEF/MSG/USE Model

Network trace tuples consist of three components:
- **DEF (Definition):** The execution path before a message is sent. **Implemented.**
- **MSG (Message):** The message type and content. **Partially implemented.**
- **USE (Usage):** The execution path after a message is received. **NOT implemented.**

### What's implemented

#### DEF — Execution path before send
**File:** `ssg-runtime/src/main/java/org/zlab/net/tracker/Runtime.java`

```java
// Ring buffer tracks the K=128 most recent branch/block IDs
private static final int K = 128;
private static final ThreadLocal<int[]> buf = ThreadLocal.withInitial(() -> new int[K]);
private static final ThreadLocal<Integer> idx = ThreadLocal.withInitial(() -> 0);

// hit() is called at each branch/block in instrumented code
public static void hit(int id) {
    int[] b = buf.get();
    int i = idx.get();
    b[i % K] = id;
    idx.set(i + 1);
}

// snapshot() captures the current ring buffer state
public static int[] snapshot() {
    int[] b = buf.get();
    return Arrays.copyOf(b, K);
}
```

When `Runtime.record()` is called at a SEND point, it:
1. Calls `snapshot()` to capture the K=128 most recent execution path entries
2. Hashes the snapshot into `TraceEntry.recentExecPathHash`
3. Stores the raw path in `TraceEntry.recentExecPath`

This means **each sent message carries a fingerprint of what code ran before it was sent**.

#### MSG — Message type detection
**File:** `ssg-runtime/src/main/java/org/zlab/net/tracker/Trace.java`

```java
public void record(String name, int id, int[] recentExecPath, Object... contextArgs) {
    // Examine if message contains fields from modified classes
    boolean changedMessage = examineChangedMessage(contextArgs);
    // Hash the execution path
    int recentExecPathHash = Arrays.hashCode(recentExecPath);
    // Create entry with verb name, path hash, changed flag, timestamp
    TraceEntry entry = new TraceEntry(id, name, recentExecPathHash, recentExecPath,
                                      changedMessage, System.nanoTime(), ...);
    traceEntries.add(entry);
}
```

MSG is partially implemented:
- Message verb is captured (e.g., `SEND_MUTATION`, `RECV_GOSSIP_DIGEST_SYN`)
- Message payload is examined via `ObjectGraphTraverser` to detect if it contains fields from classes that changed between versions (`changedMessage` flag)
- **Not captured:** Deep serialization format of the message payload (the actual bytes that differ between versions)

#### USE — Execution path after receive
**Not implemented at all.** When a message is received, `Runtime.record()` is called which captures the execution path *before* the receive point (which is really the DEF of the receiver). But the execution path *after* the message is deserialized and processed — the "use" of the message — is not tracked.

---

## Goal

Track the execution path that follows message reception. This captures **how the receiver processes the message**, which is the critical part for detecting version incompatibility: two versions may receive the same message but process it differently (or crash).

The full (DEF, MSG, USE) tuple enables:
- DEF: "What code path led to sending this message?"
- MSG: "What message was sent?"
- USE: "What code path resulted from receiving this message?"

When DEF and MSG are the same but USE differs between versions, it indicates that **the receiver's behavior changed**, which is exactly what upgrade fuzzing is looking for.

---

## Architecture Context

### Current message flow

```
Sender:                                         Receiver:
  Runtime.hit(id) ... hit(id) ... hit(id)          Runtime.hit(id) ...
  ↓                                                 ↓
  [Ring buffer filled with K=128 recent IDs]        [Ring buffer has receiver's prior path]
  ↓                                                 ↓
  Runtime.record("SEND_VERB", 1, payload)           Runtime.record("RECV_VERB", 2, payload)
  ↓                                                 ↓
  snapshot() → DEF captured                         snapshot() → captures RECEIVER'S prior path
  ↓                                                 ↓
  message.serialize(out, version)                   consumer.accept(message) ← verb handler runs
                                                    ↓
                                                    [handler code path = USE, NOT CAPTURED]
```

The RECV `Runtime.record()` call happens **before** `consumer.accept(message)` (the verb handler). So the snapshot at RECV captures the receiver's execution path *leading up to* the receive, not the path *after* processing.

### Where USE would be captured

USE = the execution path from `consumer.accept(message)` (or equivalent) until the next network event (send or receive). This is the "reaction" to the received message.

### Key Files

| File | Role |
|------|------|
| `ssg-runtime/.../net/tracker/Runtime.java` | Ring buffer, hit(), snapshot(), record() |
| `ssg-runtime/.../net/tracker/Trace.java` | TraceEntry creation, examineChangedMessage() |
| `ssg-runtime/.../net/tracker/TraceEntry.java` | Data structure: id, methodName, recentExecPath, recentExecPathHash, changedMessage, timestamp |
| `ssg-runtime/.../net/tracker/diff/DiffComputeSemanticSimilarity.java` | **[SUPERSEDED]** Computes canonical multiset Jaccard (replaced legacy Jaccard/getHashCodes) |

---

## Implementation Plan

### Approach A: Post-handler snapshot (Recommended)

Capture the execution path *after* the verb handler runs by adding a second snapshot point.

#### Step 1: Extend TraceEntry with USE field

**File:** `ssg-runtime/src/main/java/org/zlab/net/tracker/TraceEntry.java`

Add fields for the post-receive execution path:

```java
public int[] postExecPath;       // Execution path AFTER message processing
public int postExecPathHash;     // Hash of postExecPath
```

#### Step 2: Add recordPost() method to Runtime

**File:** `ssg-runtime/src/main/java/org/zlab/net/tracker/Runtime.java`

Add a method to capture the post-processing execution path and attach it to the most recent RECV entry:

```java
public static void recordPost() {
    if (trace == null) return;
    int[] postPath = snapshot();
    trace.attachPostExecPath(postPath);
}
```

#### Step 3: Add attachPostExecPath() to Trace

**File:** `ssg-runtime/src/main/java/org/zlab/net/tracker/Trace.java`

```java
public void attachPostExecPath(int[] postPath) {
    // Find the most recent RECV entry on this thread
    // Attach the post-execution path to it
    if (!traceEntries.isEmpty()) {
        TraceEntry last = traceEntries.get(traceEntries.size() - 1);
        if (last.methodName.startsWith("RECV_")) {
            last.postExecPath = postPath;
            last.postExecPathHash = Arrays.hashCode(postPath);
        }
    }
}
```

#### Step 4: Instrument the receiver — call recordPost() after verb handler

For **Cassandra 3.11.17**, in `IncomingTcpConnection.receiveMessage()`:
```java
// Existing:
try {
    org.zlab.net.tracker.Runtime.record("RECV_" + message.verb, 2, message.payload);
} catch (Exception e) {}
MessagingService.instance().receive(message, id);  // verb handler dispatched

// ADD AFTER:
try {
    org.zlab.net.tracker.Runtime.recordPost();
} catch (Exception e) {}
```

For **Cassandra 4.1.4**, in `InboundMessageHandler.ProcessMessage.run()`:
```java
// Existing:
try {
    org.zlab.net.tracker.Runtime.record("RECV_" + message.verb(), 2, message.payload);
} catch (Exception e) {}
consumer.accept(message);  // verb handler runs

// ADD AFTER:
try {
    org.zlab.net.tracker.Runtime.recordPost();
} catch (Exception e) {}
```

**Challenge:** In Cassandra 4.x, `consumer.accept(message)` dispatches the handler **asynchronously** to a stage executor. The handler doesn't run synchronously in the current thread. The `recordPost()` call would capture the dispatcher's path, not the handler's path.

**Mitigation:** For async systems, the handler itself needs instrumentation. This means finding where verb handlers are executed (e.g., `Stage.execute()` → handler.run()) and adding `recordPost()` after each handler completes.

#### Step 5: Incorporate USE into similarity computation

**File:** `ssg-runtime/src/main/java/org/zlab/net/tracker/TraceEntry.java`

Update `hashCode()` to include the post-execution path:

```java
@Override
public int hashCode() {
    // Current: hash of (methodName, recentExecPathHash)
    // New: hash of (methodName, recentExecPathHash, postExecPathHash)
    return Objects.hash(methodName.hashCode(), recentExecPathHash, postExecPathHash);
}
```

**[SUPERSEDED]** Legacy `Trace.getHashCodes()` and `DiffComputeJaccardSimilarity` have been removed. Current comparison uses canonical message keys via `DiffComputeSemanticSimilarity`.

---

### Approach B: Async-aware USE tracking (More complex, for later)

For systems where message handlers are dispatched asynchronously:

#### Step B1: Thread-correlated tracking

Associate the ring buffer snapshot with the message being processed, not just the thread:

```java
// In the verb handler thread (e.g., Cassandra Stage thread):
public static void startHandling(String messageId) {
    // Mark that this thread is now processing messageId
    currentMessageId.set(messageId);
    clearBuffer(); // Reset ring buffer for clean USE capture
}

public static void endHandling() {
    // Snapshot the buffer — this is the USE for currentMessageId
    int[] usePath = snapshot();
    trace.attachUse(currentMessageId.get(), usePath);
    currentMessageId.remove();
}
```

#### Step B2: Instrument verb handler entry/exit

For each verb handler class, inject `startHandling()` at entry and `endHandling()` at exit. This could be done automatically via Soot (instrument all implementations of `IVerbHandler.doVerb()` in 3.x or `RequestHandler.execute()` in 4.x).

---

## Comparison of DEF, MSG, USE signals for detecting version incompatibility

| Signal | What it captures | Why it matters for upgrades |
|--------|-----------------|---------------------------|
| DEF | Code path before send | Detects if sender-side serialization logic changed |
| MSG | Message content/type | Detects if the wire format or message types changed |
| USE | Code path after receive | Detects if receiver-side deserialization/handling changed |

**Most valuable for bug finding:** USE, because most upgrade bugs manifest when a new-version node receives a message from an old-version node and processes it differently (or incorrectly).

**Example:** Old node sends a MUTATION message with field X. New node's handler for MUTATION was refactored and now expects field X to have a different format. DEF is the same (old sender unchanged), MSG is the same (same bytes on wire), but USE differs (new handler takes different code path → potential crash or data corruption).

---

## Phased Rollout

### Phase 1: Synchronous USE (1 week)
- Add `recordPost()` to Runtime and TraceEntry
- Instrument Cassandra 3.11.17 (synchronous message handling in `IncomingTcpConnection`)
- Run experiment, compare traces with and without USE
- Verify that USE hashcodes differ between versions for the same message

### Phase 2: Incorporate into similarity (1 week)
- Update TraceEntry.hashCode() to include postExecPathHash
- Run Jaccard similarity experiment with USE-enriched traces
- Compare with baseline (DEF+MSG only) — expect lower similarity (more discriminating)

### Phase 3: Async-aware USE for Cassandra 4.x (2-3 weeks)
- Implement thread-correlated tracking (Approach B)
- Instrument verb handler entry/exit points
- Test with Cassandra 4.1.4

### Phase 4: Automatic USE instrumentation (parallel with Plan 2)
- Extend NetworkTraceInstrumenter (from Plan 2) to also inject `recordPost()` calls
- For automatic handler detection, instrument all implementations of known handler interfaces

---

## Risks and Considerations

1. **Async dispatch:** Cassandra 4.x dispatches handlers to thread pool stages. The ring buffer is thread-local, so `recordPost()` on the dispatcher thread doesn't capture the handler's execution path. Approach B addresses this but is more complex.

2. **Ring buffer pollution:** Between `record()` (RECV) and `recordPost()`, other code may run and pollute the ring buffer. The K=128 window may not be large enough to capture the full handler execution. Consider increasing K for USE tracking or using a separate buffer.

3. **Thread-local vs. message-correlated:** DEF naturally works with thread-local buffers because the sender prepares the message on the same thread. USE is harder because the handler may run on a different thread (or even multiple threads for multi-stage processing).

4. **Serialization size:** Adding `postExecPath` (128 ints) to every RECV TraceEntry doubles the serialized trace size for receive events. This increases memory and network overhead for trace collection.

5. **Handler identification:** To do async-aware USE, we need to know which classes implement verb handlers. This is system-specific:
   - Cassandra 3.x: `IVerbHandler` implementations
   - Cassandra 4.x: `RequestHandler` implementations + `Stage` executors
   - HDFS: `ProtobufRpcEngine2.Server.ProtobufRpcInvoker`
   - HBase: `RSRpcServices`, `MasterRpcServices`
