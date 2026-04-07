# Network Trace Instrumentation for Differential Execution

## Experiment: Cassandra 3.11.17 → 4.1.4

This document records every step taken to instrument Cassandra 3.11.17 and 4.1.4 with `Runtime.record()` calls for network trace collection, and to run the differential execution experiment.

---

## Overview

**Goal:** Capture inter-node network message traces (SEND/RECV with verb types) during differential execution of Cassandra upgrades, and compute Jaccard similarity between the trace sets of three clusters (Old-Old, Rolling, New-New).

**Previous state:** `Runtime.init()` was already added to Cassandra 3.11.17's `CassandraDaemon.java`, but no `Runtime.record()` calls existed at message send/receive points. Cassandra 4.1.4 had no instrumentation at all. Result: all traces were empty, Jaccard similarity = 1.0.

**End state:** Both versions fully instrumented. Non-empty traces collected. Jaccard similarity values of ~0.5 (Old-Old vs Rolling) and ~0.2 (Old-Old vs New-New).

---

## Prerequisites

- Docker images already built:
  - `upfuzz_cassandra:apache-cassandra-3.11.17_apache-cassandra-4.1.4`
  - `upfuzz_cassandra:apache-cassandra-4.1.4_apache-cassandra-3.11.17` (tag of same image)
- `ssgFatJar.jar` (contains `org.zlab.net.tracker.Runtime` class) already in `prebuild/cassandra/apache-cassandra-3.11.17/lib/`
- Cassandra 3.11.17 source already available at `cassandra-build/cassandra-3.11.17/`
- UpFuzz project already built

---

## Step 1: Instrument Cassandra 3.11.17

### 1a. Runtime.init() (already done)

**File:** `cassandra-build/cassandra-3.11.17/src/java/org/apache/cassandra/service/CassandraDaemon.java`

Added at line 791, in `main()` before `instance.activate()`:

```java
// Initialize network trace runtime for differential execution
try {
    org.zlab.net.tracker.Runtime.init();
    System.out.println("[UpFuzz] Network trace runtime initialized");
} catch (Exception e) {
    System.err.println("[UpFuzz] Failed to initialize network trace: " + e.getMessage());
}
```

### 1b. Runtime.record() for SEND

**File:** `cassandra-build/cassandra-3.11.17/src/java/org/apache/cassandra/net/OutboundTcpConnection.java`

Added at line 386, in `writeInternal()` before `message.serialize(out, targetVersion)`:

```java
try {
    org.zlab.net.tracker.Runtime.record("SEND_" + message.verb, 1, message.payload);
} catch (Exception e) {
    // Silently ignore instrumentation errors
}
```

### 1c. Runtime.record() for RECV

**File:** `cassandra-build/cassandra-3.11.17/src/java/org/apache/cassandra/net/IncomingTcpConnection.java`

Added at line 215, in `receiveMessage()` before `MessagingService.instance().receive(message, id)`:

```java
try {
    org.zlab.net.tracker.Runtime.record("RECV_" + message.verb, 2, message.payload);
} catch (Exception e) {
    // Silently ignore instrumentation errors
}
```

### 1d. Build

```bash
cd /home/shuai/xlab/rupfuzz/cassandra-build/cassandra-3.11.17
ant jar
# Output: build/apache-cassandra-3.11.17-SNAPSHOT.jar
```

### 1e. Deploy to prebuild

```bash
cp cassandra-build/cassandra-3.11.17/build/apache-cassandra-3.11.17-SNAPSHOT.jar \
   upfuzz/prebuild/cassandra/apache-cassandra-3.11.17/lib/apache-cassandra-3.11.17.jar
```

---

## Step 2: Instrument Cassandra 4.1.4

### 2a. Download source

```bash
cd /home/shuai/xlab/rupfuzz/cassandra-build
wget https://archive.apache.org/dist/cassandra/4.1.4/apache-cassandra-4.1.4-src.tar.gz
tar xzf apache-cassandra-4.1.4-src.tar.gz
# Extracted to: apache-cassandra-4.1.4-src/
```

### 2b. Copy ssgFatJar.jar for compilation

The `org.zlab.net.tracker.Runtime` class lives in `ssgFatJar.jar`. Cassandra 4.1.4's build.xml uses `build/lib/jars/` as the classpath, so:

```bash
# Copy to build classpath (for compilation)
mkdir -p cassandra-build/apache-cassandra-4.1.4-src/build/lib/jars
cp cassandra-build/cassandra-3.11.17/lib/ssgFatJar.jar \
   cassandra-build/apache-cassandra-4.1.4-src/build/lib/jars/

# Also copy to lib/ (not strictly needed for build, but keeps things consistent)
cp cassandra-build/cassandra-3.11.17/lib/ssgFatJar.jar \
   cassandra-build/apache-cassandra-4.1.4-src/lib/
```

### 2c. Runtime.init()

**File:** `cassandra-build/apache-cassandra-4.1.4-src/src/java/org/apache/cassandra/service/CassandraDaemon.java`

Added at line 741 in `activate()`, after `applyConfig()` and before `registerNativeAccess()`:

```java
// Initialize network trace runtime for differential execution
try {
    org.zlab.net.tracker.Runtime.init();
    System.out.println("[UpFuzz] Network trace runtime initialized");
} catch (Exception e) {
    System.err.println("[UpFuzz] Failed to initialize network trace: " + e.getMessage());
}
```

**Note:** Cassandra 4.x uses `activate()` as the entry point, unlike 3.x which uses `main()`.

### 2d. Runtime.record() for SEND (EventLoop path)

**File:** `cassandra-build/apache-cassandra-4.1.4-src/src/java/org/apache/cassandra/net/OutboundConnection.java`

Added at line 816, in `EventLoopDelivery.doRun()` before `Message.serializer.serialize(next, out, messagingVersion)`:

```java
try {
    org.zlab.net.tracker.Runtime.record("SEND_" + next.verb(), 1, next.payload);
} catch (Exception e) {
    // Silently ignore instrumentation errors
}
```

### 2e. Runtime.record() for SEND (LargeMessage path)

**File:** Same `OutboundConnection.java`

Added at line 988, in `LargeMessageDelivery.doRun()` before `Message.serializer.serialize(send, out, established.messagingVersion)`:

```java
try {
    org.zlab.net.tracker.Runtime.record("SEND_" + send.verb(), 1, send.payload);
} catch (Exception e) {
    // Silently ignore instrumentation errors
}
```

**Note:** Cassandra 4.x has two serialization paths (normal and large messages). Both must be instrumented.

### 2f. Runtime.record() for RECV

**File:** `cassandra-build/apache-cassandra-4.1.4-src/src/java/org/apache/cassandra/net/InboundMessageHandler.java`

Added at line 430, in `ProcessMessage.run()` before `consumer.accept(message)`:

```java
try {
    org.zlab.net.tracker.Runtime.record("RECV_" + message.verb(), 2, message.payload);
} catch (Exception e) {
    // Silently ignore instrumentation errors
}
```

**Note:** This is in the unified `ProcessMessage.run()` method which handles both small and large incoming messages, so a single instrumentation point covers all receive paths.

### 2g. Build

```bash
cd /home/shuai/xlab/rupfuzz/cassandra-build/apache-cassandra-4.1.4-src
ant jar
# Output: build/apache-cassandra-4.1.4-SNAPSHOT.jar
# Build time: ~2 minutes 46 seconds (full compilation of 2138 source files)
```

### 2h. Deploy to prebuild

```bash
# Copy instrumented jar
cp cassandra-build/apache-cassandra-4.1.4-src/build/apache-cassandra-4.1.4-SNAPSHOT.jar \
   upfuzz/prebuild/cassandra/apache-cassandra-4.1.4/lib/apache-cassandra-4.1.4.jar

# Copy ssgFatJar.jar (runtime dependency, loaded via Cassandra's lib/*.jar classpath)
cp cassandra-build/cassandra-3.11.17/lib/ssgFatJar.jar \
   upfuzz/prebuild/cassandra/apache-cassandra-4.1.4/lib/ssgFatJar.jar
```

---

## Step 3: Fix cqlsh_daemon.py

During testing, the cqlsh daemon kept crashing with:
```
ImportError: No module named six
```

**Root cause:** `cqlsh_daemon.py` imports `from six import StringIO`, but the Docker image's Python 2 does not have `six` installed (it's only available under Python 3).

**Fix:** Applied to both copies of the file:

```python
# Before:
from six import StringIO

# After:
try:
    from six import StringIO
except ImportError:
    from StringIO import StringIO
```

**Files modified:**
1. `upfuzz/prebuild/cassandra/apache-cassandra-3.11.17/bin/cqlsh_daemon.py`
2. `upfuzz/src/main/resources/cassandra/upgrade-testing/compile-src/cqlsh_daemon.py`

---

## Step 4: Run the Experiment

### 4a. Configuration file

Used `cass_diff_3.11.17_4.1.4.json`:

```json
{
  "originalVersion": "apache-cassandra-3.11.17",
  "upgradedVersion": "apache-cassandra-4.1.4",
  "system": "cassandra",
  "serverPort": "6399",
  "clientPort": "6400",
  "configDir": "configtests",
  "STACKED_TESTS_NUM": 1,
  "STACKED_TESTS_NUM_G2": 1,
  "nodeNum": 2,
  "faultMaxNum": 0,
  "loadInitCorpus": false,
  "saveCorpusToDisk": false,
  "testSingleVersion": false,
  "testingMode": 3,
  "useBranchCoverage": true,
  "useFormatCoverage": false,
  "useVersionDelta": false,
  "staticVD": false,
  "useTrace": true,
  "differentialExecution": true,
  "printTrace": true,
  "useCanonicalTraceSimilarity": true,
  "useCompressedOrderDebug": false,
  "debug": false,
  "drain": true,
  "cassandraEnableTimeoutCheck": true,
  "WAIT_INTERVAL": 15,
  "CASSANDRA_RETRY_TIMEOUT": 300
}
```

Key settings:
- `testingMode: 3` — uses example test plan packets
- `differentialExecution: true` — runs 3 clusters (Old-Old, Rolling, New-New)
- `useTrace: true` — enables network trace collection
- `useCanonicalTraceSimilarity: true` — windowed canonical trace similarity for corpus admission
- `nodeNum: 2` — 2-node clusters

### 4b. Required Docker images

```bash
# Both should already exist (same image, different tags):
docker images | grep upfuzz_cassandra
# upfuzz_cassandra  apache-cassandra-3.11.17_apache-cassandra-4.1.4
# upfuzz_cassandra  apache-cassandra-4.1.4_apache-cassandra-3.11.17
```

If the reversed tag doesn't exist:
```bash
docker tag upfuzz_cassandra:apache-cassandra-3.11.17_apache-cassandra-4.1.4 \
           upfuzz_cassandra:apache-cassandra-4.1.4_apache-cassandra-3.11.17
```

### 4c. Clean up before running

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz
bin/clean.sh
docker rm -f $(docker ps -aq) 2>/dev/null
```

### 4d. Start the test

```bash
# Terminal 1 (or tmux):
bin/start_server.sh cass_diff_3.11.17_4.1.4.json

# Terminal 2:
bin/start_clients.sh 1 cass_diff_3.11.17_4.1.4.json
```

### 4e. Monitor progress

```bash
# Watch server log for Jaccard similarity results:
tail -f logs/server_stdout.log | grep -i "jaccard\|trace.*len\|Similarity"

# Watch client log for cluster status:
tail -f logs/client_stdout.log | grep -i "collecting\|Received trace\|trace diff"
```

### 4f. Stop the test

```bash
pkill -f "org.zlab.upfuzz"
bin/clean.sh
```

---

## Step 5: Results

### Trace lengths (per feedback packet)

Each differential execution produces 3 pairs of traces (one pair per cluster, 2 nodes each):

| Iteration | Old-Old (node0, node1) | Rolling (node0, node1) | New-New (node0, node1) |
|-----------|------------------------|------------------------|------------------------|
| 1         | 643, 676               | 459, 561               | 780, 712               |
| 2         | 597, 631               | 421, 522               | 831, 865               |
| 3         | 624, 654               | 475, 573               | 852, 883               |

### Jaccard similarity

| Iteration | Sim[0]: Old-Old vs Rolling | Sim[1]: Rolling vs New-New |
|-----------|----------------------------|----------------------------|
| 1         | 0.5275                     | 0.2014                     |
| 2         | 0.5476                     | 0.1606                     |
| 3         | 0.4767                     | 0.2049                     |
| **Avg**   | **0.5173**                 | **0.1890**                 |

### Raw server log output

```
trace[0] len = 643
trace[1] len = 676
trace[0] len = 459
trace[1] len = 561
trace[0] len = 780
trace[1] len = 712
Jaccard Similarity[0] = 0.5274725274725275, Jaccard Similarity[1] = 0.2014388489208633

trace[0] len = 597
trace[1] len = 631
trace[0] len = 421
trace[1] len = 522
trace[0] len = 831
trace[1] len = 865
Jaccard Similarity[0] = 0.5476190476190477, Jaccard Similarity[1] = 0.16058394160583941

trace[0] len = 624
trace[1] len = 654
trace[0] len = 475
trace[1] len = 573
trace[0] len = 852
trace[1] len = 883
Jaccard Similarity[0] = 0.47674418604651164, Jaccard Similarity[1] = 0.20491803278688525
```

---

## Architecture Notes

### How prebuild jars get loaded at runtime

The Docker images do NOT contain Cassandra binaries. Instead, prebuild directories are **volume-mounted at runtime**:

```yaml
volumes:
  - ${projectRoot}/prebuild/cassandra/${version}:/cassandra/${version}
```

Cassandra's `bin/cassandra.in.sh` adds all `$CASSANDRA_HOME/lib/*.jar` to the classpath:
```bash
for jar in "$CASSANDRA_HOME"/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done
```

This means `ssgFatJar.jar` in `lib/` is automatically loaded — no Docker image rebuild required.

### Networking differences between 3.11.17 and 4.1.4

| Aspect | Cassandra 3.11.17 | Cassandra 4.1.4 |
|--------|-------------------|-----------------|
| Outbound | `OutboundTcpConnection.writeInternal()` | `OutboundConnection.EventLoopDelivery.doRun()` + `LargeMessageDelivery.doRun()` |
| Inbound | `IncomingTcpConnection.receiveMessage()` | `InboundMessageHandler.ProcessMessage.run()` |
| Message class | `MessageOut<?>`/`MessageIn` | `Message<?>` (unified) |
| Verb field | `message.verb` (enum field) | `message.verb()` (method) |
| Payload field | `message.payload` (Object) | `message.payload` (generic T) |
| Transport | Raw TCP sockets | Netty-based with frame encoding |

### Instrumentation design decisions

1. **Fully qualified class name** (`org.zlab.net.tracker.Runtime`) — avoids import conflicts with other `Runtime` classes
2. **try/catch wrapping** — prevents instrumentation from breaking normal operation if the runtime is not available
3. **Location ID** (1 for SEND, 2 for RECV) — identifies the instrumentation site
4. **Payload object** — passed for deep inspection by the trace runtime
