# Email Draft: Network Trace Instrumentation Results

---

**Subject:** Network trace instrumentation working - Jaccard similarity results for Cassandra 3.11.17 → 4.1.4

---

Hi Professor,

I have completed the network trace instrumentation for both Cassandra 3.11.17 and 4.1.4, and ran the differential execution experiment. The traces are now non-empty and the Jaccard similarity values are meaningful.

## What I did

I manually instrumented the inter-node messaging layer in both Cassandra versions to call `Runtime.record()` at every message send/receive point:

- **Cassandra 3.11.17:** Instrumented `OutboundTcpConnection.writeInternal()` (SEND) and `IncomingTcpConnection.receiveMessage()` (RECV). These are the two points where all inter-node messages pass through in the 3.x architecture.

- **Cassandra 4.1.4:** Downloaded the source and instrumented `OutboundConnection` (SEND, two paths: EventLoop and LargeMessage) and `InboundMessageHandler.ProcessMessage.run()` (RECV). The 4.x networking was rewritten with Netty, so the code structure is quite different from 3.x. I also added `Runtime.init()` to `CassandraDaemon.activate()` and deployed `ssgFatJar.jar` to the prebuild directory.

Each `Runtime.record()` call captures the message verb (e.g., `SEND_MUTATION`, `RECV_GOSSIP_DIGEST_SYN`) and the message payload object.

## Results (3 iterations)

| Iteration | Jaccard Sim[0] (Old-Old vs Rolling) | Jaccard Sim[1] (Rolling vs New-New) |
|-----------|--------------------------------------|--------------------------------------|
| 1         | 0.527                                | 0.201                                |
| 2         | 0.548                                | 0.161                                |
| 3         | 0.477                                | 0.205                                |
| **Avg**   | **0.517**                            | **0.189**                            |

The Rolling cluster is the pivot — both similarities are measured relative to it. Previously these were all 1.0 (empty traces). Now:

- **Sim[0] ~ 0.5 (Old-Old vs Rolling):** The rolling upgrade cluster shares about half its network traces with the old baseline. The upgrade process introduces different message patterns (e.g., streaming, schema migration) that diverge from the all-old cluster.

- **Sim[1] ~ 0.2 (Rolling vs New-New):** The rolling upgrade cluster shares only ~20% of its traces with the all-new cluster. This makes sense because during rolling upgrade, the cluster still has old-version nodes communicating with new-version nodes, producing very different messaging patterns from a pure new-version cluster.

Trace lengths per node ranged from 421 to 883 entries per iteration, confirming that both versions are actively generating traces.

## Bug fix along the way

I also fixed a pre-existing bug in `cqlsh_daemon.py` where `from six import StringIO` would fail because the `six` module is not installed for Python 2 in the Docker image. Added a fallback to `from StringIO import StringIO`.

## Next steps

- The Jaccard similarity is currently logged but not yet used as feedback for corpus selection in the fuzzing loop. The infrastructure to connect it is in place (`FuzzingServer.java:1512-1519`).
- Only message verbs are being recorded currently. We could also instrument the serialization/deserialization of message payloads for deeper format-level trace comparison.
- The instrumentation is manual. For other systems (HDFS, HBase, Ozone), we could explore using the Vasco/Soot-based automatic instrumentation that already exists in the codebase.

Best,
Shuai
