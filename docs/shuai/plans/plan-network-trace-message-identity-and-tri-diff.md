# Plan: Message-Identity Network Tracing and 3-Way Diff (old-old / old-new / new-new)

## 1. Problem Statement

Current net trace output is event-oriented (`SEND`, `RECV_BEGIN`, `RECV_END`) but does not carry enough stable message identity/content information to determine:

- which messages are the same across executions,
- which messages only appear in one or two executions,
- whether relative message order is preserved.

So we cannot reliably answer your target diff questions like:

- common in all three: `[m2, m5, m6]`
- only in old-old + old-new: `[m1]`
- only in new-new: `[m4]`

## 2. Investigation Findings (Current Root Causes)

### 2.1 Similarity currently ignores message content

- `Trace.getHashCodes()` uses only `entry.hashcode + "_" + entry.recentExecPathHash` (`ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java:172`).
- `entry.hashcode` is method-name hash only (`TraceEntry` constructor input) (`ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/TraceEntry.java:15`).
- Jaccard uses these hashcodes (+ 2-grams) (`ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/diff/DiffComputeJaccardSimilarity.java:25`).

Result: two events with different real messages can still look identical if method + path hash are similar.

### 2.2 Runtime data model has metadata fields, but instrumentation does not populate them

- `TraceEntry` has `nodeId`, `peerId`, `messageType`, `logicalMessageId`, `deliveryId`, `messageShapeHash` (`ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/TraceEntry.java:22`).
- But generated bridge passes `null` meta:
  - `recordSend(..., message, null, normalized)` (`rupfuzz-nettrace/scripts/instrument_prebuild_matrix.sh:101`)
  - `beginReceive(..., message, null, normalized)` (`rupfuzz-nettrace/scripts/instrument_prebuild_matrix.sh:114`)

Result: runtime logs show `node=unknown peer=null msgType=null`.

### 2.3 Message "shape" hash is too weak for identity

- `messageShapeHash` currently hashes only visited type names (no field values) (`Trace.java:42`, `Trace.java:61`, `Trace.java:80` in `ssg-runtime-shuai`).
- Traversal for large arrays/maps/collections uses random sampling (`Utils.sampleIdxFromSize`) (`ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Utils.java:93`).

Result:
- not enough semantic detail for message equality,
- potentially unstable across runs because sampling is random.

### 2.4 UpFuzz server uses Jaccard-only gating for trace signal

- `FuzzingServer.updateStatus` computes and uses Jaccard similarity for interestingness (`upfuzz-shuai/src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java:1561`).
- No 3-way message set/order diff logic exists today.

### 2.5 Runtime env wiring for node identity is missing

- Runtime expects `NET_TRACE_NODE_ID` (`ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Runtime.java:30`).
- UpFuzz docker env setup sets `ENABLE_NET_COVERAGE`, but does not set `NET_TRACE_NODE_ID` (`upfuzz-shuai/src/main/java/org/zlab/upfuzz/cassandra/CassandraDocker.java:153` and `:162`).

Result: many entries have `node=unknown`.

## 3. Design Goal (Target Behavior)

For each execution (old-old, old-new, new-new), build an ordered message sequence where each element has a stable message identity key:

`messageKey = {direction, messageType, payloadFingerprint, peer, logicalId(optional)}`

Then compute:

1. Presence diff:
   - in all three
   - in exactly two
   - only one
2. Order diff:
   - common subsequence preserving order
   - order inversions / shifted blocks
3. Optional per-node/per-direction views.

## 4. Proposed Solution

## 4.1 Data model extension (runtime)

Add stronger identity fields to `TraceEntry` in `ssg-runtime-shuai`:

- `long messageValueHash` (value-aware fingerprint)
- `String messageKey` (canonical key used by diff)
- `String messageSummary` (human-readable compact summary; truncated)
- `String traceNodeRole` or reuse `nodeId` once env is wired

Keep existing fields for compatibility.

### 4.2 Deterministic fingerprinting

Create `MessageFingerprint` utility in `org.zlab.net.tracker`:

- deterministic traversal (no random sampling),
- include primitive/string/enum values,
- include selected map/collection entries deterministically,
- bounded by depth/size budgets to control overhead,
- produce:
  - `valueHash` (for equality)
  - short `summary` (for debugging/reporting).

Important: remove randomness from fingerprint path (do not use current random sampler for identity).

### 4.3 Metadata extraction and bridge update

Update generated `NetTraceRuntimeBridge` in `rupfuzz-nettrace/scripts/instrument_prebuild_matrix.sh`:

- build and pass `SendMeta` / `RecvMeta` via reflection (instead of `null`),
- populate at least:
  - `nodeId`
  - `peerId`
  - `messageType`
  - `logicalMessageId` when available
- keep fallback to old method when runtime API is older.

For Cassandra injections:

- Send hook already has `message`, `to`, `specifyConnection`.
- Receive hook already has `message`, `header`, `peer`, `type`.
- Use these to fill metadata.

For HBase/HDFS:

- use existing arguments (`md`, `addr`, `header`, `call`, `remoteId`, etc.) to fill best-effort metadata.

### 4.4 Docker env wiring

Update UpFuzz docker launch env for Cassandra/HBase/HDFS:

- set `NET_TRACE_NODE_ID` deterministically (e.g., `<executorID>-N<index>`),
- set `ENABLE_NETWORK_TRACE=true` explicitly,
- set `NET_TRACE_PORT` from existing port config if needed.

This removes `node=unknown` and stabilizes sender/receiver identity.

### 4.5 Tri-execution diff algorithm (new)

Implement in `ssg-runtime-shuai` (or `upfuzz-shuai`, recommended in runtime lib first):

- `DiffComputeMessageTriDiff`:
  - Input: 3 traces (old-old, old-new, new-new)
  - Tokenization: `TraceEntry -> messageKey`
  - Compute multiset intersections:
    - `all3`, `only01`, `only12`, `only02`, `only0`, `only1`, `only2`
  - Compute order consistency:
    - pairwise LCS on `messageKey` sequences,
    - report longest common ordered subsequence for all three (via pairwise reduction).

Output structured report object:

- counts by category,
- concrete message keys per category,
- sample positions and timestamps,
- order mismatch statistics.

### 4.6 Server integration (replace "Jaccard only" decision)

In `upfuzz-shuai` `FuzzingServer.updateStatus`:

- keep Jaccard as secondary legacy metric,
- add new tri-diff report as primary trace signal,
- mark interesting when:
  - unexpected `only-old`/`only-new` message groups exceed threshold,
  - or order divergence exceeds threshold.

Persist tri-diff report into failure/result directory for later analysis.

## 5. Implementation Phases

## Phase 0: Baseline and guardrails

Deliverables:

- unit tests capturing current behavior (for regression safety),
- config flag to toggle new diff (`useMessageIdentityDiff`).

Files:

- `ssg-runtime-shuai/src/test/java/net/tracker/TestTrace.java`
- `upfuzz-shuai/src/main/java/org/zlab/upfuzz/fuzzingengine/Config.java`

## Phase 1: Runtime identity model

Deliverables:

- deterministic value-aware message fingerprint util,
- extended `TraceEntry`,
- `Trace.getMessageKeys()` and helper methods.

Files:

- `ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/TraceEntry.java`
- `ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Trace.java`
- `ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/ObjectGraphTraverser.java`
- `ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/Utils.java`
- new `ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/MessageFingerprint.java`

## Phase 2: Metadata population from instrumentation

Deliverables:

- bridge passes populated meta objects,
- injected hooks for Cassandra/HBase/HDFS unchanged functionally but with richer context.

Files:

- `nettrace-shuai/rupfuzz-nettrace/scripts/instrument_prebuild_matrix.sh`

Then regenerate instrumented prebuild tarballs for affected versions.

## Phase 3: UpFuzz env/node identity wiring

Deliverables:

- `NET_TRACE_NODE_ID` and trace env vars set per node.

Files:

- `upfuzz-shuai/src/main/java/org/zlab/upfuzz/cassandra/CassandraDocker.java`
- `upfuzz-shuai/src/main/java/org/zlab/upfuzz/hbase/HBaseDocker.java`
- `upfuzz-shuai/src/main/java/org/zlab/upfuzz/hdfs/HdfsDocker.java`

## Phase 4: Tri-diff computation

Deliverables:

- new comparator class and tests,
- report artifact format.

Files:

- new `ssg-runtime-shuai/src/main/java/org/zlab/net/tracker/diff/DiffComputeMessageTriDiff.java`
- new tests under `ssg-runtime-shuai/src/test/java/net/tracker/diff/`

## Phase 5: Fuzzing server integration

Deliverables:

- server logs + persisted reports showing:
  - common messages,
  - unique per execution,
  - order mismatches.

Files:

- `upfuzz-shuai/src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java`

## Phase 6: End-to-end validation

Run cassandra demo (`4.1.10 -> 5.0.6`) and verify:

1. non-null `nodeId/peerId/messageType` in traces,
2. stable `messageKey` generation across reruns for same seed/testplan,
3. tri-diff report can explicitly show sets:
   - in all 3
   - only in old-old+old-new
   - only in new-new, etc.
4. order report highlights preserved and diverged subsequences.

Then repeat smoke checks for HBase/HDFS one pair each.

## 6. Acceptance Criteria

Minimum acceptance for this goal:

1. `TraceEntry` includes value-aware message identity (`messageKey` + hash).
2. Runtime traces show meaningful sender/receiver/meta fields (not all `unknown/null`).
3. A tri-diff report is generated per diff-feedback packet.
4. Report contains explicit message groups across (old-old, old-new, new-new) and order comparison.
5. Jaccard remains optional/legacy, not the only diff signal.

## 7. Risks and Mitigations

- Performance overhead from deep fingerprint:
  - Mitigation: bounded traversal budgets + config knob.
- Cross-version schema drift:
  - Mitigation: reflection-based best-effort extraction with fallback keys.
- Compatibility with existing serialized traces:
  - Mitigation: preserve old fields and add version-tolerant handling.

## 8. Recommended Execution Order

1. Phase 1 (runtime identity) + tests.
2. Phase 3 (env/node wiring) for immediate metadata quality gain.
3. Phase 2 (bridge/meta population) and rebuild instrumented prebuilds.
4. Phase 4 + Phase 5 (tri-diff and integration).
5. Phase 6 validation on Cassandra demo first, then HBase/HDFS.

