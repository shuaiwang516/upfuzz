# Plan 2: Automating Network Trace Instrumentation

## Current State

### Manual instrumentation
Both Cassandra 3.11.17 and 4.1.4 were manually instrumented by identifying the inter-node messaging send/receive points in source code and adding `Runtime.record()` calls:

| Version | Send Point | Receive Point |
|---------|-----------|---------------|
| 3.11.17 | `OutboundTcpConnection.writeInternal()` | `IncomingTcpConnection.receiveMessage()` |
| 4.1.4   | `OutboundConnection.EventLoopDelivery.doRun()` + `LargeMessageDelivery.doRun()` | `InboundMessageHandler.ProcessMessage.run()` |

**Problems with manual instrumentation:**
1. Requires deep knowledge of each system's networking internals
2. Different systems (Cassandra, HDFS, HBase, Ozone) have completely different messaging architectures
3. Even different versions of the same system (3.x vs 4.x Cassandra) use different networking code
4. Time-consuming and error-prone
5. Must be repeated for every new version pair

### Existing automatic instrumentation (Vasco/Soot)

The project already has a Soot-based automatic instrumentation pipeline:

**File:** `vasco/src/main/java/vasco/soot/instrumenter/OutputStreamInstrumenter.java`

This instrumenter:
1. Uses Soot to analyze bytecode (no source code needed)
2. Identifies all subclasses of `java.io.OutputStream` and `java.io.DataOutput`
3. Finds all `write*()` method calls on these classes
4. Injects `org.zlab.ocov.tracker.Runtime.update(object, id)` calls before each write

**The catch:** It currently injects calls to `ocov.tracker.Runtime.update()` (format coverage), NOT `net.tracker.Runtime.record()` (network trace). The infrastructure is there but pointed at the wrong target.

---

## Goal

Create an automatic instrumentation pipeline that injects `net.tracker.Runtime.record()` calls at network message send/receive points, so that any new target system or version can be instrumented without manual effort.

---

## Architecture Context

### The Vasco/Soot Pipeline

```
vasco/
├── src/main/java/vasco/soot/
│   ├── instrumenter/
│   │   ├── OutputStreamInstrumenter.java    ← Current: instruments OutputStream.write*()
│   │   ├── InputStreamInstrumenter.java     ← Placeholder for read-side
│   │   └── ... (other instrumenters)
│   └── Main.java                            ← Entry point, runs Soot transforms
├── build.gradle                             ← Builds the instrumenter jar
```

### How Soot instrumentation works

1. Soot loads the target application's bytecode (compiled .class files or .jar)
2. It builds an intermediate representation (Jimple) of all methods
3. The instrumenter finds call sites matching a pattern (e.g., `OutputStream.write()`)
4. It injects new Jimple statements before/after those call sites
5. The modified bytecode is written back to .class files

### ssgFatJar.jar contents

The runtime jar (`ssgFatJar.jar`) contains both:
- `org.zlab.ocov.tracker.Runtime` — format coverage tracking (used by OutputStreamInstrumenter)
- `org.zlab.net.tracker.Runtime` — network trace tracking (what we want)

Both runtimes are already packaged. Only the instrumentation targeting needs to change.

### Key Files

| File | Role |
|------|------|
| `vasco/.../OutputStreamInstrumenter.java` | Current Soot instrumenter for OutputStream.write — **template for new instrumenter** |
| `vasco/.../InputStreamInstrumenter.java` | May exist as template for read-side |
| `ssg-runtime/.../net/tracker/Runtime.java` | Target runtime: `record(String name, int id, Object... contextArgs)` |
| `ssg-runtime/.../ocov/tracker/Runtime.java` | Current target: `update(Object, int)` |

---

## Implementation Plan

### Approach A: Adapt OutputStreamInstrumenter (Recommended)

The key insight: **all inter-node messages eventually pass through `OutputStream.write()` or `DataOutput.write*()`**. The existing OutputStreamInstrumenter already finds these call sites. We just need to inject `net.tracker.Runtime.record()` instead of (or in addition to) `ocov.tracker.Runtime.update()`.

#### Step 1: Create NetworkTraceInstrumenter

**File:** `vasco/src/main/java/vasco/soot/instrumenter/NetworkTraceInstrumenter.java`

Fork `OutputStreamInstrumenter.java` and modify:

1. Change the injected method from:
   ```java
   Scene.v().getMethod("<org.zlab.ocov.tracker.Runtime: boolean update(java.lang.Object,int)>");
   ```
   to:
   ```java
   Scene.v().getMethod("<org.zlab.net.tracker.Runtime: void record(java.lang.String,int,java.lang.Object[])>");
   ```

2. At each `write*()` call site, construct the record call:
   ```java
   // name = "SEND_" + enclosing class name (identifies the messaging layer)
   // id = unique instrumentation point ID
   // contextArgs = the object being written
   Runtime.record("SEND_" + className, pointId, writtenObject);
   ```

3. For the receive side, also instrument `InputStream.read*()` and `DataInput.read*()` calls:
   ```java
   Runtime.record("RECV_" + className, pointId, readObject);
   ```

#### Step 2: Add filtering to focus on network-related classes

**Problem:** Not all `OutputStream.write()` calls are network messages. Many are file I/O, logging, etc. Instrumenting everything would create enormous, noisy traces.

**Solution:** Filter by class hierarchy or package name:
- **Option A: Socket-based filtering** — Only instrument `write()` calls where the `OutputStream` is a subclass of `java.net.SocketOutputStream` or where the containing class handles network connections. Soot can resolve this via points-to analysis.
- **Option B: Package-based filtering** — Only instrument classes in networking packages (e.g., `org.apache.cassandra.net.*`, `org.apache.hadoop.ipc.*`). Requires a system-specific config file.
- **Option C: Call graph analysis** — Use Soot's call graph to trace which `write()` calls eventually reach a `Socket.getOutputStream()`. This is the most precise but most expensive.

**Recommendation:** Start with Option B (package filtering) as it's simplest and can be configured per system. The filter can be specified in a config file alongside other instrumentation parameters.

#### Step 3: Integrate with the build pipeline

The current build flow for instrumentation:
```
1. Build target system from source (ant/maven/gradle)
2. Run Vasco/Soot on the compiled bytecode
3. Copy instrumented jars to prebuild/
4. ssgFatJar.jar is also in prebuild/ (provides runtime classes)
```

Add a Gradle task or script that:
```bash
# Example for Cassandra 3.11.17
java -cp vasco.jar vasco.soot.Main \
  --instrumenter NetworkTraceInstrumenter \
  --input cassandra-build/cassandra-3.11.17/build/apache-cassandra-3.11.17-SNAPSHOT.jar \
  --output prebuild/cassandra/apache-cassandra-3.11.17/lib/apache-cassandra-3.11.17.jar \
  --filter "org.apache.cassandra.net.*"
```

#### Step 4: Handle Runtime.init() injection

The instrumenter must also inject `Runtime.init()` at the application entry point. `OutputStreamInstrumenter` already does this (lines 207-214): it checks if the method is the main method and injects `Runtime.init()`.

For Cassandra 4.x, the entry point is `activate()` not `main()`. The instrumenter needs a configurable entry point:
```
--initMethod "org.apache.cassandra.service.CassandraDaemon.activate"
```

#### Step 5: Handle verb/message type extraction

Manual instrumentation captures the message verb (e.g., `SEND_MUTATION`). Automatic instrumentation at the `OutputStream.write()` level doesn't have direct access to the verb.

**Options:**
1. **Use class name as proxy:** `SEND_OutboundTcpConnection` identifies the sender; the verb is lost but the sending context is preserved.
2. **Stack inspection:** Walk the call stack to find the caller that has the verb. Expensive at runtime.
3. **Combine with DEF:** The execution path (DEF) already captured by `Runtime.hit()` implicitly encodes which verb is being processed, since different verbs take different code paths.

**Recommendation:** Option 1 (class name) for initial implementation. Option 3 (DEF) already provides implicit verb discrimination through execution path diversity.

### Approach B: Direct bytecode instrumentation (Alternative)

Instead of Soot, use ASM (Java bytecode manipulation framework) to:
1. Scan all `.class` files for calls to `OutputStream.write()` or `DataOutput.write*()`
2. Inject `Runtime.record()` before each call
3. This is simpler than Soot (no need for whole-program analysis) but less precise

The ASM approach is already used by JaCoCo (which UpFuzz relies on), so the infrastructure is familiar.

---

## System-Specific Considerations

### Cassandra
- 3.x: Messages go through `OutboundTcpConnection` → raw `OutputStream`
- 4.x: Messages go through `OutboundConnection` → Netty `ChannelOutboundBuffer` (not a standard OutputStream)
- **Challenge:** Cassandra 4.x uses Netty, which doesn't use `OutputStream.write()` directly. The instrumenter would need to also handle `io.netty.buffer.ByteBuf.writeBytes()` and `io.netty.channel.Channel.writeAndFlush()`.

### HDFS
- Uses `org.apache.hadoop.ipc.Client` → `DataOutputStream`
- Standard `OutputStream.write()` pattern — well-suited for automatic instrumentation

### HBase
- Uses `org.apache.hadoop.hbase.ipc.RpcClient` → standard I/O
- Similar to HDFS pattern

### Ozone
- Uses gRPC → Netty underneath
- Similar challenges to Cassandra 4.x with Netty

**Implication:** For Netty-based systems, the instrumenter needs to target Netty-specific APIs in addition to standard `OutputStream`.

---

## Phased Rollout

### Phase 1: Proof of concept (1-2 weeks)
- Fork `OutputStreamInstrumenter.java` → `NetworkTraceInstrumenter.java`
- Change target from `ocov.tracker.Runtime.update()` to `net.tracker.Runtime.record()`
- Test on Cassandra 3.11.17 (uses standard `OutputStream`)
- Compare traces with manual instrumentation results

### Phase 2: Filtering and precision (1-2 weeks)
- Add package-based filtering to reduce noise
- Add configurable entry point for `Runtime.init()`
- Test on HDFS (standard `DataOutputStream` pattern)

### Phase 3: Netty support (2-3 weeks)
- Add instrumentation for `ByteBuf.writeBytes()` and `Channel.writeAndFlush()`
- Test on Cassandra 4.x and Ozone
- Compare with manual instrumentation results

### Phase 4: Receive-side instrumentation (1-2 weeks)
- Instrument `InputStream.read*()` and `DataInput.read*()`
- For Netty: `ByteBuf.readBytes()` and `ChannelInboundHandler.channelRead()`

---

## Risks and Considerations

1. **Noise:** Instrumenting all `write()` calls produces enormous traces with file I/O, logging, etc. Filtering is critical.
2. **Netty gap:** The biggest challenge. Cassandra 4.x and Ozone use Netty, which bypasses `OutputStream`. Need to instrument Netty-specific APIs.
3. **Performance:** Per-message instrumentation adds overhead. The existing `try/catch` wrapper helps, but high-volume messaging could be impacted.
4. **Precision vs. completeness trade-off:** Package filtering may miss messages that pass through generic utility classes. Call graph analysis is more precise but much harder to implement.
5. **Multi-version compatibility:** The instrumenter must work with different Java versions (8, 11, 17) and different bytecode formats.
