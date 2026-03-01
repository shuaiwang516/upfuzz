# Detailed Report: Cassandra Traced Messages (Rolling Upgrade Demo)

Date: 2026-02-24  
System: Cassandra rolling-upgrade fuzzing (4.1.10 -> 5.0.6)

## 1. Scope and data sources

This report documents **what network messages are actually traced** in the Cassandra demo and how they are represented.

Primary sources used:

- Verbose trace capture (full `TraceEntry{...}` lines):
  - `scripts/runner/results/cassandra_410_to_506_trace_verbose/server_stdout.log`
  - Config used: `useTrace=true`, `printTrace=true`
  - `scripts/runner/results/cassandra_410_to_506_trace_verbose/config.json`
- Latest semkey verification run (tri-diff summary after fixes):
  - `scripts/runner/results/cassandra_4_1_10_to_5_0_6_msg_identity_semkey_verify_fixed_grace_2026_02_24/server_stdout.log`

Parsed dataset generated from verbose trace:

- `/tmp/cassandra_410_to_506_trace_verbose_entries.tsv`
- Total parsed trace entries: `7559`

## 2. Trace entry schema (what is recorded per message event)

`TraceEntry` carries these network/message identity fields:

- `eventType` (`SEND`, `RECV_BEGIN`, `RECV_END`)
- `nodeId`, `peerId`, `channel`, `protocol`
- `messageType`, `messageVersion`
- `logicalMessageId`, `deliveryId`
- `messageShapeHash`, `messageValueHash`
- `messageKey`
- `messageSummary`
- `log` (payload class/type hint)

Code references:

- `TraceEntry` fields and `toString`:
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/TraceEntry.java:9`
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/TraceEntry.java:112`
- Fill path for SEND/RECV_BEGIN/RECV_END:
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java:49`
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java:74`
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java:98`

## 3. Cluster and endpoint mapping in this run

Three executions inside one diff packet:

- `Only Old` cluster node IDs: `E30hhZHI-N0`, `E30hhZHI-N1`
- `Rolling` cluster node IDs: `CRikbfEh-N0`, `CRikbfEh-N1`
- `Only New` cluster node IDs: `38cwm9YW-N0`, `38cwm9YW-N1`

Peer IP sets:

- `Only Old`: `192.168.222.2`, `192.168.222.3`
- `Rolling`: `192.168.187.2`, `192.168.187.3`
- `Only New`: `192.168.33.2`, `192.168.33.3`

## 4. Total traced volume

### 4.1 Entries per execution

- `Only Old`: `2532`
- `Rolling`: `2586`
- `Only New`: `2441`

### 4.2 Entries by event type (all executions combined)

- `SEND`: `2528`
- `RECV_BEGIN`: `2516`
- `RECV_END`: `2515`

### 4.3 `messageVersion` population

- `messageVersion='null'`: `7559` entries (all entries in this verbose capture)

## 5. Message catalog: what types are traced

### 5.1 Global counts by `messageType`

| messageType | count |
|---|---:|
| GOSSIP_DIGEST_SYN | 2684 |
| GOSSIP_DIGEST_ACK | 2294 |
| GOSSIP_DIGEST_ACK2 | 2272 |
| ECHO_REQ | 69 |
| ECHO_RSP | 57 |
| PING_REQ | 54 |
| PING_RSP | 54 |
| SCHEMA_PUSH_REQ | 48 |
| GOSSIP_SHUTDOWN | 6 |
| READ_RSP | 6 |
| SCHEMA_PULL_REQ | 6 |
| SCHEMA_PULL_RSP | 6 |
| READ_REQ | 3 |

### 5.2 Per-execution counts by message type

| execution | messageType | count |
|---|---|---:|
| Only Old | GOSSIP_DIGEST_SYN | 900 |
| Only Old | GOSSIP_DIGEST_ACK | 768 |
| Only Old | GOSSIP_DIGEST_ACK2 | 762 |
| Only Old | ECHO_REQ | 22 |
| Only Old | ECHO_RSP | 18 |
| Only Old | PING_REQ | 18 |
| Only Old | PING_RSP | 18 |
| Only Old | SCHEMA_PUSH_REQ | 15 |
| Only Old | SCHEMA_PULL_REQ | 3 |
| Only Old | SCHEMA_PULL_RSP | 3 |
| Only Old | GOSSIP_SHUTDOWN | 2 |
| Only Old | READ_REQ | 1 |
| Only Old | READ_RSP | 2 |
| Rolling | GOSSIP_DIGEST_SYN | 909 |
| Rolling | GOSSIP_DIGEST_ACK | 790 |
| Rolling | GOSSIP_DIGEST_ACK2 | 782 |
| Rolling | ECHO_REQ | 22 |
| Rolling | ECHO_RSP | 18 |
| Rolling | PING_REQ | 18 |
| Rolling | PING_RSP | 18 |
| Rolling | SCHEMA_PUSH_REQ | 18 |
| Rolling | SCHEMA_PULL_REQ | 3 |
| Rolling | SCHEMA_PULL_RSP | 3 |
| Rolling | GOSSIP_SHUTDOWN | 2 |
| Rolling | READ_REQ | 1 |
| Rolling | READ_RSP | 2 |
| Only New | GOSSIP_DIGEST_SYN | 875 |
| Only New | GOSSIP_DIGEST_ACK | 736 |
| Only New | GOSSIP_DIGEST_ACK2 | 728 |
| Only New | ECHO_REQ | 25 |
| Only New | ECHO_RSP | 21 |
| Only New | PING_REQ | 18 |
| Only New | PING_RSP | 18 |
| Only New | SCHEMA_PUSH_REQ | 15 |
| Only New | GOSSIP_SHUTDOWN | 2 |
| Only New | READ_REQ | 1 |
| Only New | READ_RSP | 2 |

Observation:

- `SCHEMA_PULL_REQ` and `SCHEMA_PULL_RSP` appear in `Only Old` and `Rolling`, but not in `Only New` in this capture.

### 5.3 Message type to payload/log class mapping (`TraceEntry.log`)

| messageType | payload/log class | count |
|---|---|---:|
| GOSSIP_DIGEST_SYN | org.apache.cassandra.gms.GossipDigestSyn | 2684 |
| GOSSIP_DIGEST_ACK | org.apache.cassandra.gms.GossipDigestAck | 2294 |
| GOSSIP_DIGEST_ACK2 | org.apache.cassandra.gms.GossipDigestAck2 | 2272 |
| ECHO_REQ | org.apache.cassandra.net.NoPayload | 69 |
| ECHO_RSP | org.apache.cassandra.net.NoPayload | 57 |
| PING_REQ | org.apache.cassandra.net.PingRequest | 54 |
| PING_RSP | org.apache.cassandra.net.NoPayload | 54 |
| SCHEMA_PUSH_REQ | java.util.ArrayList | 32 |
| SCHEMA_PUSH_REQ | java.util.HashMap$Values | 16 |
| SCHEMA_PULL_REQ | org.apache.cassandra.net.NoPayload | 6 |
| SCHEMA_PULL_RSP | java.util.ArrayList | 6 |
| READ_REQ | org.apache.cassandra.db.SinglePartitionReadCommand | 3 |
| READ_RSP | org.apache.cassandra.db.ReadResponse$RemoteDataResponse | 6 |
| GOSSIP_SHUTDOWN | org.apache.cassandra.net.NoPayload | 4 |
| GOSSIP_SHUTDOWN | null | 2 |

## 6. Event-level breakdown by message type

| messageType | SEND | RECV_BEGIN | RECV_END |
|---|---:|---:|---:|
| GOSSIP_DIGEST_SYN | 897 | 894 | 893 |
| GOSSIP_DIGEST_ACK | 762 | 766 | 766 |
| GOSSIP_DIGEST_ACK2 | 760 | 756 | 756 |
| ECHO_REQ | 31 | 19 | 19 |
| ECHO_RSP | 19 | 19 | 19 |
| PING_REQ | 18 | 18 | 18 |
| PING_RSP | 18 | 18 | 18 |
| SCHEMA_PUSH_REQ | 16 | 16 | 16 |
| SCHEMA_PULL_REQ | 2 | 2 | 2 |
| SCHEMA_PULL_RSP | 2 | 2 | 2 |
| READ_REQ | 3 | 0 | 0 |
| READ_RSP | 0 | 3 | 3 |
| GOSSIP_SHUTDOWN | 0 | 3 | 3 |

## 7. Sender/receiver directional distribution (representative)

Top SEND directions are gossip-heavy and balanced between the 2 nodes in each execution.

Examples:

- `Only Old`:
  - `E30hhZHI-N1 -> 192.168.222.2` `GOSSIP_DIGEST_SYN`: `176`
  - `E30hhZHI-N1 -> 192.168.222.2` `GOSSIP_DIGEST_ACK2`: `131`
  - `E30hhZHI-N0 -> 192.168.222.3` `GOSSIP_DIGEST_ACK`: `127`
- `Rolling`:
  - `CRikbfEh-N1 -> 192.168.187.2` `GOSSIP_DIGEST_SYN`: `176`
  - `CRikbfEh-N0 -> 192.168.187.3` `GOSSIP_DIGEST_ACK`: `131`
- `Only New`:
  - `38cwm9YW-N1 -> 192.168.33.2` `GOSSIP_DIGEST_SYN`: `173`
  - `38cwm9YW-N0 -> 192.168.33.3` `GOSSIP_DIGEST_ACK2`: `119`

RECV_BEGIN shows the corresponding opposite-side receives for the same message families.

## 8. Message identity richness (shape/value diversity)

For each `(execution, messageType)`, we computed distinct counts of:

- `messageShapeHash`: structure/type-shape diversity
- `messageValueHash`: payload/value diversity

Highlights from SEND-side:

- `Only Old`:
  - `GOSSIP_DIGEST_SYN`: `SEND_count=300`, `uniqueShapeHash=77`, `uniqueValueHash=275`
  - `GOSSIP_DIGEST_ACK`: `256`, `41`, `256`
  - `GOSSIP_DIGEST_ACK2`: `254`, `35`, `254`
- `Rolling`:
  - `GOSSIP_DIGEST_SYN`: `304`, `71`, `281`
  - `GOSSIP_DIGEST_ACK`: `262`, `42`, `262`
  - `GOSSIP_DIGEST_ACK2`: `262`, `40`, `262`
- `Only New`:
  - `GOSSIP_DIGEST_SYN`: `293`, `5`, `266`
  - `GOSSIP_DIGEST_ACK`: `244`, `9`, `244`
  - `GOSSIP_DIGEST_ACK2`: `244`, `3`, `244`

Interpretation:

- `messageValueHash` diversity is high in all three executions for gossip traffic.
- `messageShapeHash` diversity differs substantially (notably lower in `Only New` in this run), which is expected because shape hash is sensitive to traversed object-type graph structure.

## 9. Representative traced message bodies (`messageSummary`)

Representative first-seen samples per message type:

| type | event | node -> peer | logicalId | summary prefix |
|---|---|---|---:|---|
| GOSSIP_DIGEST_SYN | SEND | `E30hhZHI-N1 -> 192.168.222.2` | 1 | `Message|Header|...|GOSSIP_DIGEST_SYN|GossipDigestSyn|dev_cluster|ArrayList|...` |
| GOSSIP_DIGEST_ACK | SEND | `E30hhZHI-N0 -> 192.168.222.3` | 1 | `Message|Header|...|GOSSIP_DIGEST_ACK|GossipDigestAck|HashMap|{size=1}|...` |
| GOSSIP_DIGEST_ACK2 | SEND | `E30hhZHI-N1 -> 192.168.222.2` | 3 | `Message|Header|...|GOSSIP_DIGEST_ACK2|GossipDigestAck2|HashMap|{size=1}|...` |
| ECHO_REQ | SEND | `E30hhZHI-N0 -> 192.168.222.3` | 5 | `Message|Header|...|ECHO_REQ|NoPayload|...` |
| ECHO_RSP | SEND | `E30hhZHI-N1 -> 192.168.222.2` | 5 | `Message|Header|...|ECHO_RSP|NoPayload|...` |
| PING_REQ | SEND | `E30hhZHI-N0 -> 192.168.222.3` | 165 | `Message|Header|...|PING_REQ|PingRequest|SMALL_MESSAGES|...` |
| PING_RSP | SEND | `E30hhZHI-N1 -> 192.168.222.2` | 165 | `Message|Header|...|PING_RSP|NoPayload|...` |
| SCHEMA_PUSH_REQ | SEND | `E30hhZHI-N0 -> 192.168.222.3` | 138 | `Message|Header|...|SCHEMA_PUSH_REQ|Values|(size=1)|Mutation|...` |
| SCHEMA_PULL_REQ | SEND | `E30hhZHI-N1 -> 192.168.222.2` | 140 | `Message|Header|...|SCHEMA_PULL_REQ|NoPayload|...` |
| SCHEMA_PULL_RSP | SEND | `E30hhZHI-N0 -> 192.168.222.3` | 140 | `Message|Header|...|SCHEMA_PULL_RSP|ArrayList|(size=3)|Mutation|...` |
| READ_REQ | SEND | `E30hhZHI-N1 -> 192.168.222.2` | 335 | `Message|Header|...|READ_REQ|SinglePartitionReadCommand|...` |
| READ_RSP | RECV_BEGIN | `E30hhZHI-N1 <- 192.168.222.2` | 335 | `Message|Header|...|READ_RSP|RemoteDataResponse|HeapByteBuffer|...` |
| GOSSIP_SHUTDOWN | RECV_BEGIN | `E30hhZHI-N1 <- 192.168.222.2` | 345 | `Message|Header|...|GOSSIP_SHUTDOWN|NoPayload|...` |

Detailed source rows are available from:

- `/tmp/cassandra_sample_messages.tsv`

## 10. Protocol flow evidence from trace entries

### 10.1 Gossip handshake style sequence

Early trace entries show repeated `SYN -> ACK -> ACK2` with corresponding `RECV_BEGIN/RECV_END`:

- Example:
  - `entry[0]` SEND `GOSSIP_DIGEST_SYN` (`logicalMessageId=1`)
  - `entry[1]` RECV_BEGIN `GOSSIP_DIGEST_SYN`
  - `entry[2]` SEND `GOSSIP_DIGEST_ACK`
  - `entry[11]` SEND `GOSSIP_DIGEST_ACK2`

### 10.2 Echo/ping liveness probes

- `ECHO_REQ`/`ECHO_RSP` around logical IDs `4`, `5`
- `PING_REQ`/`PING_RSP` around logical IDs `30`, `31`, `165`, `304`
- Message summaries clearly include `PingRequest` and `NoPayload` classes.

### 10.3 Schema exchange

- `SCHEMA_PUSH_REQ` appears in all three executions.
- `SCHEMA_PULL_REQ` and `SCHEMA_PULL_RSP` appear in `Only Old` and `Rolling`.
- Summaries show `Mutation` payload structures in push/pull response paths.

### 10.4 Read command path

- `READ_REQ` observed as SEND.
- `READ_RSP` observed as RECV_BEGIN/RECV_END.
- Summary includes `SinglePartitionReadCommand` and `ReadResponse$RemoteDataResponse`.

## 11. Why this is enough to identify SAME/DIFF messages

Trace identity fields now include actual content fingerprints:

- `messageType`
- `messageShapeHash`
- `messageValueHash`
- `messageSummary` (token stream over payload/header object graph)
- `payload class` (`TraceEntry.log`)

Diff key construction and semantic normalization:

- `buildMessageDiffKey` uses event/method/type/version/payload + semantic summary hash
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java:358`
- `semanticSummaryHash` normalizes volatile tokens (`numbers`, `UUID`, `len/size`, etc.) and hashes canonical tokens
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java:369`
  - `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java:399`

This allows cross-execution comparison of message identity beyond raw `SEND/RECV` labels.

## 12. Tri-diff status: old verbose run vs fixed semkey run

### 12.1 Old verbose trace run (`printTrace=true`)

From `scripts/runner/results/cassandra_410_to_506_trace_verbose/server_stdout.log`:

- `MessageTriDiff{len0=846, len1=865, len2=817, all3=0, only0=846, only1=865, only2=817, in01Only=0, in12Only=0, in02Only=0, lcs012=0, orderRatio=0.0000}`

This was from the earlier keying behavior.

### 12.2 Fixed semkey run (`printTrace=false`)

From `scripts/runner/results/cassandra_4_1_10_to_5_0_6_msg_identity_semkey_verify_fixed_grace_2026_02_24/server_stdout.log`:

- Round 1:
  - `MessageTriDiff{len0=770, len1=780, len2=755, all3=748, only0=7, only1=15, only2=5, in01Only=15, in12Only=2, in02Only=0, lcs012=719, orderRatio=0.9523}`
- Round 2:
  - `MessageTriDiff{len0=850, len1=832, len2=832, all3=819, only0=17, only1=3, only2=7, in01Only=9, in12Only=1, in02Only=5, lcs012=781, orderRatio=0.9387}`

Interpretation:

- The fixed semkey design now finds large shared core (`all3`) and high ordered overlap (`orderRatio`), while still isolating differences (`only*`, `in*Only` categories).

Tri-diff implementation reference:

- `/home/shuai/xlab/rupfuzz/ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/diff/DiffComputeMessageTriDiff.java:18`

## 13. Final verification statement

For this Cassandra rolling-upgrade demo, traced network messages include:

- Gossip synchronization/control (`SYN`, `ACK`, `ACK2`, `GOSSIP_SHUTDOWN`)
- Liveness probes (`ECHO_REQ/RSP`, `PING_REQ/RSP`)
- Schema exchange (`SCHEMA_PUSH_REQ`, `SCHEMA_PULL_REQ/RSP`)
- Application read path (`READ_REQ`, `READ_RSP`)

And each traced event carries sufficient identity detail (type + payload/summary-derived fingerprints + semantic keying) to support meaningful OLD/ROLLING/NEW comparison.
