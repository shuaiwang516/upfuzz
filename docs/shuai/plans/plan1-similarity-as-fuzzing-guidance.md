# Plan 1: Make Jaccard Similarity into Fuzzing Guidance

## Current State

### What works
- Differential execution runs 3 clusters: Old-Old, Rolling, New-New
- Network traces are collected from all 3 clusters (verified with Cassandra 3.11.17 → 4.1.4)
- Jaccard similarity is computed between Old-Old vs Rolling (Sim[0]) and Rolling vs New-New (Sim[1])
- Typical values: Sim[0] ~ 0.5, Sim[1] ~ 0.2

### What's missing
The similarity is **logged but never used as feedback** for corpus selection. The test plan diff feedback path (`updateStatus(TestPlanDiffFeedbackPacket)` in `FuzzingServer.java:1445-1521`) computes the similarity and logs it, then **returns without updating the corpus or scheduling any follow-up action**.

In contrast, the stacked test feedback path (`updateStatus(StackedFeedbackPacket)` in `FuzzingServer.java:1530+`) feeds branch coverage and format coverage signals into `corpus.addSeed()` (line 1695-1698), which determines test prioritization.

---

## Goal

Use the Jaccard similarity values to guide corpus selection: tests that produce **low similarity** (divergent network behavior between clusters) should be prioritized for further mutation, because they are more likely to exercise version-incompatible code paths.

---

## Architecture Context

### Corpus Selection Pipeline

```
FuzzingServer.updateStatus(StackedFeedbackPacket)
  ├─ Check branch coverage (newOriBC, newUpgradeBC)
  ├─ Check format coverage (newOriFC, newModFC, newBoundaryChange)
  └─ corpus.addSeed(seed, newOriBC, newOriFC, newUpgradeBC, newBoundaryChange, newModFC)
       └─ InterestingTestsCorpus selects queue based on flags:
            Queue 0: FORMAT_COVERAGE_VERSION_DELTA  (highest priority)
            Queue 1: BRANCH_COVERAGE_VERSION_DELTA
            Queue 2: FORMAT_COVERAGE
            Queue 3: BRANCH_COVERAGE_BEFORE_VERSION_CHANGE
            Queue 4: BOUNDARY_BROKEN
            Queue 5: LOW_PRIORITY
```

The multi-queue corpus (`InterestingTestsCorpus.java`) uses a weighted random selection across 6 priority tiers. Tests in higher-priority queues are selected more frequently for mutation.

### Key Files

| File | Role |
|------|------|
| `upfuzz/.../FuzzingServer.java:1445-1521` | `updateStatus(TestPlanDiffFeedbackPacket)` — computes similarity, currently dead-end |
| `upfuzz/.../FuzzingServer.java:1530-1710` | `updateStatus(StackedFeedbackPacket)` — feeds coverage into corpus |
| `upfuzz/.../InterestingTestsCorpus.java` | Multi-queue corpus with 6 priority tiers and addSeed/getPacket methods |
| `upfuzz/.../Config.java:316-326` | `useTrace`, `differentialExecution`, `useJaccardSimilarity` flags |
| `ssg-runtime/.../diff/DiffComputeJaccardSimilarity.java` | Computes Jaccard using Apache DataSketches on 2-grams of trace hash codes |

---

## Implementation Plan

### Step 1: Define "interesting" threshold for similarity

**Decision needed:** What Jaccard similarity value indicates an "interesting" divergence?

Options:
- **Option A: Relative threshold** — Track a running average of Sim[0] and Sim[1]. A test is interesting if its similarity is below (average - 1 stddev). This adapts as the corpus evolves.
- **Option B: Absolute threshold** — Use a fixed cutoff (e.g., Sim < 0.3). Simple but may not generalize across systems.
- **Option C: Any decrease** — Track the minimum similarity seen so far. A test is interesting if it produces a new minimum. Similar to how branch coverage tracks "new bits."

**Recommendation:** Option C (any decrease / new minimum similarity) is most consistent with how UpFuzz handles branch and format coverage. It also avoids needing to tune thresholds.

### Step 2: Add a new priority tier to InterestingTestsCorpus

**File:** `upfuzz/src/main/java/org/zlab/upfuzz/fuzzingengine/server/InterestingTestsCorpus.java`

Add a new tier in the `Type` enum for network trace divergence:

```java
enum Type {
    FORMAT_COVERAGE_VERSION_DELTA,     // 0 (highest)
    BRANCH_COVERAGE_VERSION_DELTA,     // 1
    NETWORK_TRACE_DIVERGENCE,          // 2 (NEW)
    FORMAT_COVERAGE,                   // 3 (was 2)
    BRANCH_COVERAGE_BEFORE_VERSION_CHANGE, // 4 (was 3)
    BOUNDARY_BROKEN,                   // 5 (was 4)
    LOW_PRIORITY                       // 6 (was 5)
}
```

Update `intermediateBuffer` array size from 6 to 7.

**Rationale:** Network trace divergence should be high priority because it directly measures version-incompatible network behavior, which is the core goal of upgrade testing.

### Step 3: Track minimum similarity in FuzzingServer

**File:** `upfuzz/src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java`

Add fields to track the minimum observed Jaccard similarity:

```java
private double minJaccardSim0 = 1.0;  // Old-Old vs Rolling
private double minJaccardSim1 = 1.0;  // Rolling vs New-New
```

### Step 4: Connect similarity to corpus in updateStatus(TestPlanDiffFeedbackPacket)

**File:** `upfuzz/src/main/java/org/zlab/upfuzz/fuzzingengine/server/FuzzingServer.java`

After the similarity computation at line 1512-1519, add logic to feed the result into the corpus:

```java
if (Config.getConf().useJaccardSimilarity) {
    double[] diff = DiffComputeJaccardSimilarity.compute(...);
    logger.info("Jaccard Similarity[0] = " + diff[0] + ", Jaccard Similarity[1] = " + diff[1]);

    // NEW: Check if this test produced more divergent network behavior
    boolean newNetworkDivergence = false;
    if (diff[0] < minJaccardSim0 || diff[1] < minJaccardSim1) {
        newNetworkDivergence = true;
        minJaccardSim0 = Math.min(minJaccardSim0, diff[0]);
        minJaccardSim1 = Math.min(minJaccardSim1, diff[1]);
        logger.info("New network trace divergence detected! " +
                     "minSim[0]=" + minJaccardSim0 + ", minSim[1]=" + minJaccardSim1);
    }

    // Feed into corpus
    if (newNetworkDivergence) {
        corpus.addSeed(seed, InterestingTestsCorpus.Type.NETWORK_TRACE_DIVERGENCE);
    }
}
```

### Step 5: Extend addSeed to accept a Type parameter

**File:** `upfuzz/src/main/java/org/zlab/upfuzz/fuzzingengine/server/InterestingTestsCorpus.java`

Add an overload of `addSeed` that accepts a specific `Type`:

```java
public void addSeed(Seed seed, Type type) {
    // Add to the specified queue
    addPacket(type, seed.configFileName, seed.testPacket);
}
```

### Step 6: Bridge the test plan feedback to seed tracking

**Problem:** The `updateStatus(TestPlanDiffFeedbackPacket)` path currently doesn't have a `Seed` object to add to the corpus, because test plan packets are tracked in `testID2TestPlan` (line 1431) rather than `testID2Seed`.

**Solution:** Either:
- (a) Store test plan seeds in `testID2Seed` alongside stacked test seeds, OR
- (b) Create a new `testPlanID2Seed` map

This requires tracing how test plan packets are generated and dispatched to understand where the seed should be created.

### Step 7: Add Config flag

**File:** `upfuzz/src/main/java/org/zlab/upfuzz/fuzzingengine/Config.java`

```java
public boolean useNetworkTraceGuidance = false;  // Use Jaccard similarity as corpus feedback
```

### Step 8: Also consider changedMessage flag

The trace already tracks whether messages contain modified fields (`TraceEntry.changedMessage`). This could be a secondary signal: if a test produces traces where messages contain fields from classes that changed between versions, it's directly exercising version-delta code.

**File:** `FuzzingServer.java` in `updateStatus(TestPlanDiffFeedbackPacket)`, lines 1476-1489 already check `changedMessage` but only log it. Connect this to the corpus as well.

---

## Testing Plan

1. Run with `useNetworkTraceGuidance: true` alongside existing coverage
2. Verify that tests producing low similarity get prioritized
3. Compare bug-finding effectiveness vs. baseline (coverage-only) over 24h runs
4. Monitor queue sizes to ensure network trace divergence queue isn't dominating

---

## Risks and Considerations

1. **Seed tracking gap:** The test plan diff path and stacked test path use different data structures. Bridging them requires careful refactoring.
2. **Threshold sensitivity:** "New minimum" may be too aggressive (only first few tests are interesting) or too lenient (many tests produce marginal decreases). May need a minimum delta (e.g., decrease by at least 0.01).
3. **Performance:** Differential execution runs 3 clusters per test, 3x the resource cost. The guidance must produce enough value to justify this overhead.
4. **Interaction with other signals:** Network trace divergence should complement, not replace, branch and format coverage. Need to ensure the multi-queue weighting doesn't starve other signals.
