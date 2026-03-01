Subject: Detailed update: network tracing design and Cassandra rolling-upgrade demo (4.1.10 -> 5.0.6)

Hi [Advisor Name],

I am sending a detailed update on (1) how the network tracing is designed, (2) what I observed in the Cassandra rolling-upgrade demo, and (3) known issues/blockers.

1) Network tracing design (high-level + implementation details)

I currently do network message tracing in two layers:

1. Instrumentation layer (system-specific source hooks)
- Instrumentation is injected into the target system source (Cassandra/HBase/HDFS) and packed into prebuilt tarballs.
- For Cassandra demo, I used instrumented sources for 4.1.10 and 5.0.6.
- Hook points are message send and receive processing paths so I capture network events at:
  - `SEND`
  - `RECV_BEGIN`
  - `RECV_END`

2. Runtime trace collection layer (`ssg-runtime`)
- Each hook emits a `TraceEntry` with:
  - endpoint metadata: `nodeId`, `peerId`, `channel`, `protocol`
  - message metadata: `messageType`, `messageVersion`, `logicalMessageId`, `deliveryId`
  - identity fields: `messageShapeHash`, `messageValueHash`, `messageSummary`, `messageKey`
  - execution context: `beforeExecPath`, `afterExecPath`
- Identity computation:
  - `messageShapeHash`: hash of the traversed object-type set
  - `messageValueHash`: hash over message/object values from fingerprinting
  - `messageSummary`: normalized tokenized summary of message object graph
- Diff key for tri-comparison uses event/method/type/version/payload class + semantic summary hash (normalized to remove volatile tokens like raw numbers/UUIDs/length fields).

Rolling-upgrade comparison path
- One diff packet contains three executions of the same test plan:
  - old-old, rolling(old-new), new-new
- Server merges traces per execution (timestamp order), then computes:
  - old Jaccard metric (legacy)
  - new 3-way message tri-diff categories:
    - `all3`, `only0`, `only1`, `only2`, `in01Only`, `in12Only`, `in02Only`
  - order consistency via `lcs012` and `orderRatio`


2) Cassandra demo execution results

I ran a demo execution on Cassandra with the 4.1.10 -> 5.0.6 upgrade path.

- Round 1
  - `len0=770, len1=780, len2=755`
  - `all3=748`
  - `only0=7, only1=15, only2=5`
  - `in01Only=15, in12Only=2, in02Only=0`
  - `lcs012=719, orderRatio=0.9523`

- Round 2
  - `len0=850, len1=832, len2=832`
  - `all3=819`
  - `only0=17, only1=3, only2=7`
  - `in01Only=9, in12Only=1, in02Only=5`
  - `lcs012=781, orderRatio=0.9387`

Interpretation:
- "len*" means total messages in that execution; "all" means the message identity is present in all three executions; "only" means it's present only in that execution; "in*Only" means it's present in those two executions but not the third; `lcs012` is the longest common subsequence length across all three executions; `orderRatio` is the ratio of `lcs012` to the average execution length.
- Most message identities are shared across old-old/rolling/new-new (`all3` is high).
- There are still structured differences (non-zero `only*` and `in*Only`), which is expected and useful for upgrade differential analysis.
- Order overlap is high (`~0.94-0.95`), so executions are largely aligned but not identical.

I further analyzed the raw trace entries to understand what message types and payload classes are being captured, and how they correlate with the diff categories.
The results are below:

- Parsed total entries: 7559
  - Only Old: 2532
  - Rolling: 2586
  - Only New: 2441
- Event totals:
  - `SEND=2528`, `RECV_BEGIN=2516`, `RECV_END=2515`

Tracked message types (global counts):
- `GOSSIP_DIGEST_SYN` 2684
- `GOSSIP_DIGEST_ACK` 2294
- `GOSSIP_DIGEST_ACK2` 2272
- `ECHO_REQ` 69, `ECHO_RSP` 57
- `PING_REQ` 54, `PING_RSP` 54
- `SCHEMA_PUSH_REQ` 48
- `SCHEMA_PULL_REQ` 6, `SCHEMA_PULL_RSP` 6
- `READ_REQ` 3, `READ_RSP` 6
- `GOSSIP_SHUTDOWN` 6

What this confirms:
- The tracing correctly only tracking SEND/RECV labels but also tracking actual message families and payload class context.
- Example payload/log classes observed:
  - `GossipDigestSyn`, `GossipDigestAck`, `GossipDigestAck2`
  - `PingRequest`
  - `SinglePartitionReadCommand` (READ_REQ)
  - `ReadResponse$RemoteDataResponse` (READ_RSP)
  - schema mutations in push/pull paths (`ArrayList`, `HashMap$Values`)

Example protocol evidence from raw trace entries:
- Repeated gossip handshake structure: `SYN -> ACK -> ACK2`
- Liveness cycles: `ECHO_REQ/RSP`, `PING_REQ/RSP` (both SMALL and LARGE message paths observed)
- Schema paths: `SCHEMA_PUSH_REQ` in all 3 executions; `SCHEMA_PULL_REQ/RSP` present in old-old and rolling in this run
- Read path: `READ_REQ` folloId by `READ_RSP`


There are known issues for improvement, but I will do it later, I listed here for discussion:

1. Cross-node message correlation is still not fully canonical
- I have `logicalMessageId`/`deliveryId`, but send/receive-side matching can still be imperfect due to per-node/local counters and directional context.
- Action needed: strengthen correlation keying so one logical network message maps more robustly across sender/receiver events.

2. Startup noise during bootstrap (connection-refused bursts)
- I still see transient `Connection refused` during cluster bring-up windows.
- In verified runs this did not cause execution failure, but it can add noise and timing variance.
- Action needed: tighten readiness/wait strategy before workload dispatch.

My next step is to actaully start running the Cassandra with three version pairs rolling upgrades and see whether we can find interesting issues/bugs.
I will write the scripts to setup the Cloudlab environment and automate the execution tonight and hopefully start the runs by tomorrow.

Best,  
Shuai
