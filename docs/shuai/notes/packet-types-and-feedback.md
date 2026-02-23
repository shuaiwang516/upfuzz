# PacketType Overview

The 12 packet types fall into three categories: **control** (1), **test requests** sent server→agent (3), and **feedback responses** sent agent→server (8).

## Control Packet
| Type | Value | Direction |
|------|-------|-----------|
| `RegisterPacket` | 0 | Agent → Server (handshake) |

## Test Packets (Server → Agent)
| Type | Value | What it sends |
|------|-------|---------------|
| `StackedTestPacket` | 1 | Batch of simple command-sequence tests (write cmds + validation cmds) |
| `TestPlanPacket` | 4 | Single event-driven test plan (upgrade ops, fault injections, shell commands) |
| `MixedTestPacket` | 6 | Combines one `StackedTestPacket` + one `TestPlanPacket` |

## Feedback Packets (Agent → Server)
| Type | Value | Responds to |
|------|-------|-------------|
| `FeedbackPacket` | 3 | Individual test (embedded inside `StackedFeedbackPacket`) |
| `StackedFeedbackPacket` | 2 | `StackedTestPacket` |
| `TestPlanFeedbackPacket` | 5 | `TestPlanPacket` |
| `MixedFeedbackPacket` | 7 | `MixedTestPacket` |
| `VersionDeltaFeedbackPacket` | 10 | (abstract placeholder, no class) |
| `VersionDeltaFeedbackPacketApproach1` | 11 | `StackedTestPacket` (1-group VD) |
| `VersionDeltaFeedbackPacketApproach2` | 12 | `StackedTestPacket` (2-group VD) |
| `TestPlanDiffFeedbackPacket` | 13 | `TestPlanPacket` (differential comparison) |

---

## Detailed Feedback Comparison

### 1. `StackedFeedbackPacket` → `updateStatus(StackedFeedbackPacket)` (FuzzingServer.java:1530)

**Scenario:** Standard full-stop upgrade testing. Runs commands on old version, upgrades all nodes, runs validation on new version.

**Coverage tracked:**
- **Branch coverage (BC):** 2 dimensions — `curOriCoverage` (old version) and `curUpCoverageAfterUpgrade` (new version after upgrade)
- **Format coverage (FC):** `oriObjCoverage` only (old version's object graph). Checks for new formats, matchable formats, multi-inv broken, isSerialized, and boundary changes.

**Corpus update:** Calls `corpus.addSeed(seed, newOriBC, newOriFC, newUpgradeBC, newBoundaryChange, newModFC)` — 5 boolean signals. The seed is considered interesting if it triggers new branch or format coverage in either the old or upgraded version.

**Bug detection:**
- Upgrade process crash → `saveFullStopCrashReport`
- Downgrade process crash (if `testDowngrade` enabled) → `saveFullStopCrashReport`
- Per-test inconsistency (old vs new read results differ) → `saveInconsistencyReport`
- ERROR logs → `saveErrorReport`
- Execution timeout / unexpected exception → early return with report

**FullStopCorpus:** Each test's seed + validation results are added to `fullStopCorpus` for later use by test plan generation.

---

### 2. `TestPlanFeedbackPacket` → `updateStatus(TestPlanFeedbackPacket)` (FuzzingServer.java:1372)

**Scenario:** Rolling upgrade testing with fault injection. Events include per-node upgrades, link/node/isolate failures, shell commands interleaved.

**Coverage tracked:**
- **Branch coverage:** Same 2 dimensions (`curOriCoverage`, `curUpCoverageAfterUpgrade`), but merged from the combined feedback array.
- **No format coverage** — format coverage is not processed here.
- **Network traces:** `Trace[]` array is logged (trace length per node), but currently only used for debugging.

**Corpus update:** If new BC is found, adds to `testPlanCorpus` (separate from the main seed corpus).

**Bug detection:**
- Event failure (e.g., a node crashed during rolling upgrade) → `saveEventCrashReport`
- Inconsistency → `saveInconsistencyReport`
- ERROR logs → `saveErrorReport`

**Key difference from StackedFeedback:** No per-test format coverage, no `fullStopCorpus` update, no seed-level tracking. The test plan is tracked as a whole unit in `testPlanCorpus`.

---

### 3. `MixedFeedbackPacket` → dispatched to both updateStatus methods

**Scenario:** `testingMode == 2`. Combines full-stop + rolling upgrade in one execution. The agent runs the stacked tests first, then executes the test plan (upgrade happens during the test plan).

**Handling:** The handler simply unpacks the two sub-packets and calls:
1. `fuzzingServer.updateStatus(stackedFeedbackPacket)` — same as #1 above
2. `fuzzingServer.updateStatus(testPlanFeedbackPacket)` — same as #2 above

**No additional logic** beyond what the two individual handlers do.

---

### 4. `VersionDeltaFeedbackPacketApproach1` → `analyzeFeedbackFromVersionDelta()` (FuzzingServer.java:1740)

**Scenario:** 1-group version delta. The same agent runs the test on **both** the upgrade path (old→new) and the downgrade path (new→old), returning two `StackedFeedbackPacket` objects.

**Coverage tracked — 4 dimensions of BC:**
| Coverage store | Source | Meaning |
|---|---|---|
| `curOriCoverage` | upgrade.originalCodeCoverage | Old version before upgrade |
| `curUpCoverageAfterUpgrade` | upgrade.upgradedCodeCoverage | New version after upgrade |
| `curUpCoverage` | downgrade.originalCodeCoverage | New version before downgrade |
| `curOriCoverageAfterDowngrade` | downgrade.downgradedCodeCoverage | Old version after downgrade |

**Format coverage — 2 dimensions:**
- `oriObjCoverage` — from the upgrade path's format data
- `upObjCoverage` — from the downgrade path's format data
- Tracks matchable formats to compute **version delta** (XOR of ori vs up)

**Version delta computation:**
- `newBCVD = newOriBC ^ newUpBC` — branch coverage version delta (coverage differs between versions)
- `newFCVD = newOriMatchableFC ^ newUpMatchableFC` — format coverage version delta

**Corpus update:** `corpus.addSeed(seed, newOriBC, newUpBC, newOriFC, newUpFC, newUpgradeBC, newDowngradeBC, newOriBoundaryChange, newUpBoundaryChange, false, newBCVD, newFCVD)` — **12 boolean signals** (vs 5 for StackedFeedback). Uses `CorpusVersionDeltaFiveQueueWithBoundary`.

**Key difference:** This is the most comprehensive feedback. It captures asymmetric behavior: something covered in old but not new (or vice versa) is a **version delta**, the core signal for detecting upgrade-related bugs.

---

### 5. `VersionDeltaFeedbackPacketApproach2` — 2-group version delta

This uses **two agent groups** working in a pipeline:

#### Group 1: `analyzeFeedbackFromVersionDeltaGroup1()` (FuzzingServer.java:1951)

**Scenario:** Group 1 agents run tests on old→new (upgrade) and new→old (downgrade) paths, same as Approach1. But additionally, the feedback includes the `TestPacket` list itself.

**Coverage tracked:** Same 2 BC dimensions (ori, up) + 2 FC dimensions (ori, up) as Approach1, but only for the old and new versions (no post-upgrade/post-downgrade tracking).

**Version delta + priority classification:** After computing version delta, tests are classified into a **priority queue** (`testBatchCorpus`) with these types (highest to lowest priority):
1. `FORMAT_COVERAGE_VERSION_DELTA` — format coverage differs between versions
2. `BRANCH_COVERAGE_VERSION_DELTA` — branch coverage differs between versions
3. `FORMAT_COVERAGE` — new format coverage (but same in both versions)
4. `BOUNDARY_BROKEN` — boundary invariant broken
5. `BRANCH_COVERAGE_BEFORE_VERSION_CHANGE` — new branch coverage (but same in both versions)
6. `LOW_PRIORITY` — non-interesting tests (kept with probability `1 - DROP_TEST_PROB_G2`)

These classified tests are then sent to Group 2 agents.

#### Group 2: `analyzeFeedbackFromVersionDeltaGroup2()` (FuzzingServer.java:2207)

**Scenario:** Group 2 agents receive the prioritized tests from Group 1 and run them through an **actual upgrade**. They only track `curUpCoverageAfterUpgrade` (post-upgrade branch coverage).

**Coverage tracked:** Only 1 BC dimension (`curUpCoverageAfterUpgrade`). No format coverage.

**Corpus update:** `corpus.addSeed(seed, false, false, newUpgradeBC, false, false)` — only the upgrade BC signal matters.

---

### 6. `TestPlanDiffFeedbackPacket` → `updateStatus(TestPlanDiffFeedbackPacket)` (FuzzingServer.java:1445)

**Scenario:** The same test plan is executed under 3 conditions: (1) old version only, (2) rolling upgrade, (3) new version only. Returns 3 `TestPlanFeedbackPacket` results for differential comparison.

**Coverage tracked:** None updated to corpus. This is purely an **analysis** packet.

**Differential analysis:**
- Merges per-node traces into serialized `Trace` objects using timestamp ordering
- Computes **edit distance** between: (rolling-upgrade trace vs old-only trace) and (rolling-upgrade trace vs new-only trace)
- Computes **Jaccard similarity** between the same pairs
- Checks for "changed messages" in traces

**Corpus update:** None. No seeds are added.

**Bug detection:** Purely diagnostic. A large edit distance or low Jaccard similarity between the rolling upgrade trace and both old/new traces indicates the rolling upgrade introduced behavioral divergence.

---

## Summary Table

| Feedback Type | BC Dimensions | FC Dimensions | Version Delta | Corpus Target | Bug Detection |
|---|---|---|---|---|---|
| **StackedFeedback** | 2 (ori, upAfterUpgrade) | 1 (ori only) | No | Main corpus (5 signals) | Crash, inconsistency, error log |
| **TestPlanFeedback** | 2 (ori, upAfterUpgrade) | 0 | No | TestPlan corpus | Event crash, inconsistency, error log |
| **MixedFeedback** | Delegates to above two | Delegates | No | Both corpora | Both sets |
| **VD Approach1** | 4 (ori, upAfterUpgrade, up, oriAfterDowngrade) | 2 (ori, up) + matchable | **Yes** (BC XOR, FC XOR) | Main corpus (12 signals) | Crash, inconsistency, error log |
| **VD Approach2 G1** | 2 (ori, up) | 2 (ori, up) | **Yes** + priority classification | Main corpus + testBatchCorpus | Error log |
| **VD Approach2 G2** | 1 (upAfterUpgrade) | 0 | No (consumes G1's priorities) | Main corpus (1 signal) | Inconsistency, error log |
| **TestPlanDiff** | 0 | 0 | Trace-level diff | None | Edit distance / Jaccard divergence |
