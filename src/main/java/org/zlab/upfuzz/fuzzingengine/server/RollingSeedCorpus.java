package org.zlab.upfuzz.fuzzingengine.server;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zlab.upfuzz.Command;
import org.zlab.upfuzz.CommandSequence;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;

/**
 * Phase 2 tiered corpus for mode-5 rolling differential fuzzing.
 *
 * <p>Prior to Phase 2 the rolling corpus was a single cycle queue that
 * accepted every interesting seed without any reservation policy. The Apr 12
 * campaign showed this let trace-only seeds dominate the corpus
 * (0.85 save rate, 1959 trace-only adds) and crowd out branch-novel seeds.
 *
 * <p>Phase 2 splits the corpus into three pools:
 * <ul>
 *   <li>{@code branchBackedPool} — admitted due to new branch coverage
 *       (with or without an accompanying trace signal). Always retained.</li>
 *   <li>{@code traceProbationPool} — admitted due to trace signals alone.
 *       Held in probation until the seed pays off downstream; then it is
 *       promoted to the long-lived trace pool. If it never pays off it is
 *       evicted after the configured timeout or selection budget.</li>
 *   <li>{@code tracePromotedPool} — trace-only seeds that have already
 *       produced downstream branch hits, structured tri-lane candidates,
 *       or enough independent rediscoveries.</li>
 * </ul>
 *
 * <p>Admission caps:
 * <ul>
 *   <li>Branch-backed seeds are unconditionally admitted.</li>
 *   <li>Trace-only admissions are capped at
 *       {@code traceOnlyAdmissionCapPerRound} per round.</li>
 *   <li>Trace-only admissions are capped at
 *       {@code traceOnlyAdmissionCapPer100Rounds} over any sliding
 *       100-round window.</li>
 *   <li>The total trace-only pool (probation + promoted) is capped at
 *       {@code floor(rollingCorpusMaxSize * traceOnlyCorpusMaxShare)}.</li>
 * </ul>
 *
 * <p>Parent selection is weighted by {@code branchBackedSelectionWeight}:
 * each call to {@link #getSeed()} first chooses between the branch-backed
 * and promoted-trace pools using that weight (default 0.5), then
 * round-robins within the chosen pool. If the chosen pool is empty the
 * selection falls through to the other long-lived pool, and finally to the
 * probation pool so newly admitted trace-only seeds still have a chance to
 * exercise and earn promotion.
 *
 * <p>Promotion for trace-probation seeds fires on any of:
 * <ul>
 *   <li>A downstream branch-coverage hit credited to this seed's lineage.</li>
 *   <li>A downstream structured (Checker D) tri-lane candidate credited
 *       to this seed's lineage.</li>
 *   <li>An independent rediscovery: the same command-sequence content is
 *       admitted again while this seed is still in probation, from a
 *       *distinct parent lineage root* that has not already contributed a
 *       rediscovery. Repeated admissions from the same parent do not
 *       count — the Apr 12 plan explicitly requires the rediscovery to
 *       come from another independently mutated seed. After
 *       {@code traceProbationRediscoveryThreshold} distinct-parent
 *       rediscoveries the seed is promoted.</li>
 * </ul>
 *
 * <p>Once a probation seed is promoted, its content-hash entry is
 * released from {@code probationByContentHash} so subsequent identical
 * trace-only admissions are evaluated fresh rather than silently
 * swallowed as stale rediscoveries. Rediscovery-driven dedup is Phase 4's
 * job, not Phase 2's.
 *
 * <p>Eviction for trace-probation seeds fires on either of:
 * <ul>
 *   <li>{@code creationRound + traceOnlyProbationRounds} elapsed without
 *       any payoff.</li>
 *   <li>At least
 *       {@code traceProbationMaxSelectionsWithoutPayoff} selections as a
 *       parent, all without payoff.</li>
 * </ul>
 *
 * <p>Thread safety: all public methods are {@code synchronized}. In practice
 * the corpus is only touched from {@link FuzzingServer}'s already-synchronized
 * update / getOneTest methods, but the extra guard is cheap and makes the
 * class safely usable from unit tests that do not wrap it.
 */
public class RollingSeedCorpus {
    private static final Logger logger = LogManager
            .getLogger(RollingSeedCorpus.class);

    /**
     * Outcome of a Phase 2 admission attempt, exposed so the server can log
     * whether a seed was accepted, rejected, or absorbed as a rediscovery.
     */
    public enum AdmissionOutcome {
        /** Added to {@code branchBackedPool}. */
        BRANCH_BACKED,
        /** Added to {@code traceProbationPool}. */
        TRACE_PROBATION,
        /**
         * Not stored as a new seed — the content was already in probation,
         * so the existing entry's rediscovery counter was bumped instead
         * (and possibly promoted).
         */
        REDISCOVERED,
        /** Rejected: trace-only per-round cap hit. */
        REJECTED_PER_ROUND_CAP,
        /** Rejected: trace-only sliding 100-round cap hit. */
        REJECTED_WINDOW_CAP,
        /** Rejected: trace-only pool share cap hit. */
        REJECTED_SHARE_CAP;

        public boolean isAdmitted() {
            return this == BRANCH_BACKED || this == TRACE_PROBATION;
        }
    }

    public static final int DEFAULT_ROLLING_CORPUS_MAX_SIZE = 500;
    public static final double DEFAULT_TRACE_ONLY_CORPUS_MAX_SHARE = 0.33;
    public static final int DEFAULT_TRACE_ONLY_ADMISSION_CAP_PER_ROUND = 1;
    public static final int DEFAULT_TRACE_ONLY_ADMISSION_CAP_PER_100_ROUNDS = 15;
    public static final int DEFAULT_TRACE_ONLY_PROBATION_ROUNDS = 50;
    public static final int DEFAULT_TRACE_PROBATION_MAX_SELECTIONS = 10;
    public static final int DEFAULT_TRACE_PROBATION_REDISCOVERY_THRESHOLD = 3;
    public static final double DEFAULT_BRANCH_BACKED_SELECTION_WEIGHT = 0.5;

    private final int rollingCorpusMaxSize;
    private final double traceOnlyCorpusMaxShare;
    private final int traceOnlyAdmissionCapPerRound;
    private final int traceOnlyAdmissionCapPer100Rounds;
    private final int traceOnlyProbationRounds;
    private final int traceProbationMaxSelectionsWithoutPayoff;
    private final int traceProbationRediscoveryThreshold;
    private final double branchBackedSelectionWeight;

    private final LinkedList<RollingSeed> branchBackedPool = new LinkedList<>();
    private final LinkedList<RollingSeed> traceProbationPool = new LinkedList<>();
    private final LinkedList<RollingSeed> tracePromotedPool = new LinkedList<>();

    private final Map<Integer, RollingSeed> seedByLineage = new HashMap<>();
    // Content hash → probation seed. This index tracks the *currently
    // probation* seed for a given command-sequence content, and is used
    // exclusively to bump rediscovery counters. The index MUST be released
    // when a seed is promoted (via {@link #promote}) or evicted (via
    // {@link #evictExpiredProbation}); otherwise later identical trace-only
    // admissions would be silently swallowed as stale rediscoveries
    // (Phase 4's responsibility, not Phase 2's). Uses LinkedHashMap so
    // iteration during the linear eviction scan is deterministic across
    // runs.
    private final Map<String, RollingSeed> probationByContentHash = new LinkedHashMap<>();

    private final Deque<Long> recentTraceOnlyAdditionRounds = new ArrayDeque<>();
    private long currentRound = -1L;
    private int traceOnlyAddsInCurrentRound = 0;

    private int branchBackedCursor = 0;
    private int tracePromotedCursor = 0;
    private int traceProbationCursor = 0;

    private final Random selectionRand;

    // Phase 2 observability counters. Exposed via accessors for the
    // per-run status line and for tests.
    private long totalBranchBackedAdds = 0;
    private long totalTraceProbationAdds = 0;
    private long totalTracePromoted = 0;
    private long totalTraceRediscoveries = 0;
    private long totalTraceRejectedPerRound = 0;
    private long totalTraceRejectedWindow = 0;
    private long totalTraceRejectedShare = 0;
    private long totalTraceProbationEvicted = 0;

    public RollingSeedCorpus() {
        this(
                DEFAULT_ROLLING_CORPUS_MAX_SIZE,
                DEFAULT_TRACE_ONLY_CORPUS_MAX_SHARE,
                DEFAULT_TRACE_ONLY_ADMISSION_CAP_PER_ROUND,
                DEFAULT_TRACE_ONLY_ADMISSION_CAP_PER_100_ROUNDS,
                DEFAULT_TRACE_ONLY_PROBATION_ROUNDS,
                DEFAULT_TRACE_PROBATION_MAX_SELECTIONS,
                DEFAULT_TRACE_PROBATION_REDISCOVERY_THRESHOLD,
                DEFAULT_BRANCH_BACKED_SELECTION_WEIGHT,
                new Random(0xdeadbeefL));
    }

    public RollingSeedCorpus(
            int rollingCorpusMaxSize,
            double traceOnlyCorpusMaxShare,
            int traceOnlyAdmissionCapPerRound,
            int traceOnlyAdmissionCapPer100Rounds,
            int traceOnlyProbationRounds,
            int traceProbationMaxSelectionsWithoutPayoff,
            int traceProbationRediscoveryThreshold,
            double branchBackedSelectionWeight,
            Random selectionRand) {
        this.rollingCorpusMaxSize = Math.max(1, rollingCorpusMaxSize);
        this.traceOnlyCorpusMaxShare = clamp01(traceOnlyCorpusMaxShare);
        this.traceOnlyAdmissionCapPerRound = Math.max(0,
                traceOnlyAdmissionCapPerRound);
        this.traceOnlyAdmissionCapPer100Rounds = Math.max(0,
                traceOnlyAdmissionCapPer100Rounds);
        this.traceOnlyProbationRounds = Math.max(1, traceOnlyProbationRounds);
        this.traceProbationMaxSelectionsWithoutPayoff = Math.max(1,
                traceProbationMaxSelectionsWithoutPayoff);
        this.traceProbationRediscoveryThreshold = Math.max(1,
                traceProbationRediscoveryThreshold);
        this.branchBackedSelectionWeight = clamp01(branchBackedSelectionWeight);
        this.selectionRand = selectionRand == null ? new Random(0xdeadbeefL)
                : selectionRand;
    }

    // === Admission path ===

    /**
     * Legacy/bootstrap entry point. Equivalent to admitting the seed as a
     * branch-backed seed at an unknown round, without any admission-reason
     * classification. Used by the mode-5 bootstrap path and by legacy
     * callers so they do not need to know about Phase 2 machinery.
     */
    public synchronized void addSeed(RollingSeed seed) {
        if (seed == null) {
            return;
        }
        seed.seedClass = SeedClass.BRANCH_BACKED;
        if (seed.creationRound < 0) {
            seed.creationRound = currentRound;
        }
        if (!branchBackedPool.contains(seed)) {
            branchBackedPool.add(seed);
            if (seed.lineageTestId >= 0) {
                seedByLineage.put(seed.lineageTestId, seed);
            }
            totalBranchBackedAdds++;
        }
    }

    /**
     * Phase 2 admission path without an explicit parent lineage root.
     * Equivalent to calling {@link #tryAdmit(RollingSeed, AdmissionReason,
     * long, int)} with {@code parentLineageRoot = -1}, meaning any
     * rediscovery seen under this call is attributed to the "unknown
     * parent" bucket and will only count once toward promotion. Kept for
     * legacy callers and tests that do not care about rediscovery
     * semantics.
     */
    public synchronized AdmissionOutcome tryAdmit(
            RollingSeed seed,
            AdmissionReason reason,
            long round) {
        return tryAdmit(seed, reason, round, /*parentLineageRoot*/ -1);
    }

    /**
     * Phase 2 admission path. Classifies the seed by {@code reason},
     * applies caps for trace-only admissions, and routes the seed into the
     * matching pool.
     *
     * <p>{@code parentLineageRoot} is the lineage root of the packet that
     * produced this candidate seed, resolved via
     * {@code ObservabilityMetrics.resolveRootLifecycleId}. It is used
     * exclusively by the rediscovery path to enforce the "independent
     * parents" requirement from the Apr 12 master plan: a probation seed
     * is only credited with a rediscovery event when the new admission
     * comes from a parent root that has not already contributed a
     * rediscovery (and that is not the probation seed itself). Values
     * below zero are treated as an "unknown parent" bucket that can
     * contribute at most one rediscovery, so we do not silently reward
     * the same orphaned seed repeatedly.
     *
     * @return the outcome of the admission attempt. The caller should
     *         consult {@link AdmissionOutcome#isAdmitted()} before adding
     *         the same seed to any downstream queue (e.g. testPlanCorpus)
     *         so that cap rejections also stop trace-only flooding of the
     *         short-term execution queue.
     */
    public synchronized AdmissionOutcome tryAdmit(
            RollingSeed seed,
            AdmissionReason reason,
            long round,
            int parentLineageRoot) {
        if (seed == null || reason == null) {
            return AdmissionOutcome.REJECTED_SHARE_CAP;
        }
        advanceRound(round);

        boolean isBranchBacked = reason == AdmissionReason.BRANCH_ONLY
                || reason == AdmissionReason.BRANCH_AND_TRACE;
        if (isBranchBacked) {
            seed.seedClass = SeedClass.BRANCH_BACKED;
            seed.creationRound = round;
            if (!branchBackedPool.contains(seed)) {
                branchBackedPool.add(seed);
                if (seed.lineageTestId >= 0) {
                    seedByLineage.put(seed.lineageTestId, seed);
                }
                totalBranchBackedAdds++;
            }
            return AdmissionOutcome.BRANCH_BACKED;
        }

        // Trace-only path below. First check rediscovery, which is cheap
        // and has no admission cost — a rediscovery does not consume the
        // per-round or per-100-rounds budget. Note that the content-hash
        // index is released as soon as a probation seed is promoted, so a
        // match here genuinely means the existing entry is still in the
        // probation pool.
        String contentHash = contentHashOf(seed);
        if (contentHash != null) {
            RollingSeed existing = probationByContentHash.get(contentHash);
            if (existing != null) {
                // Independent-parent gate. Count at most one rediscovery
                // per distinct parent-lineage root, and never count a
                // rediscovery that comes from the probation seed itself
                // (a mutation loop that landed back on the same content).
                boolean sameAsSelf = parentLineageRoot >= 0
                        && parentLineageRoot == existing.lineageTestId;
                boolean independentParent = !sameAsSelf
                        && existing.independentRediscoveryParents
                                .add(parentLineageRoot);
                if (independentParent) {
                    existing.rediscoveryCount++;
                    totalTraceRediscoveries++;
                    if (existing.rediscoveryCount >= traceProbationRediscoveryThreshold
                            && existing.seedClass == SeedClass.TRACE_PROBATION) {
                        promote(existing, "rediscovery");
                    }
                }
                return AdmissionOutcome.REDISCOVERED;
            }
        }

        // Cap 1: per-round.
        if (traceOnlyAddsInCurrentRound >= traceOnlyAdmissionCapPerRound) {
            totalTraceRejectedPerRound++;
            return AdmissionOutcome.REJECTED_PER_ROUND_CAP;
        }

        // Cap 2: sliding 100-round window.
        pruneOldAdditionRounds(round);
        if (recentTraceOnlyAdditionRounds
                .size() >= traceOnlyAdmissionCapPer100Rounds) {
            totalTraceRejectedWindow++;
            return AdmissionOutcome.REJECTED_WINDOW_CAP;
        }

        // Cap 3: total trace-only share.
        int traceOnlyPoolSize = traceProbationPool.size()
                + tracePromotedPool.size();
        int traceOnlyCap = (int) Math
                .floor(rollingCorpusMaxSize * traceOnlyCorpusMaxShare);
        if (traceOnlyPoolSize >= traceOnlyCap) {
            totalTraceRejectedShare++;
            return AdmissionOutcome.REJECTED_SHARE_CAP;
        }

        seed.seedClass = SeedClass.TRACE_PROBATION;
        seed.creationRound = round;
        traceProbationPool.add(seed);
        if (seed.lineageTestId >= 0) {
            seedByLineage.put(seed.lineageTestId, seed);
        }
        if (contentHash != null) {
            probationByContentHash.put(contentHash, seed);
        }
        traceOnlyAddsInCurrentRound++;
        recentTraceOnlyAdditionRounds.addLast(round);
        totalTraceProbationAdds++;
        return AdmissionOutcome.TRACE_PROBATION;
    }

    // === Downstream payoff hooks ===

    /**
     * Credit the rolling-corpus seed identified by {@code lineageTestId}
     * for a downstream new-branch-coverage hit. If the seed is currently
     * in probation, this promotes it.
     */
    public synchronized void notifyBranchPayoff(int lineageTestId) {
        RollingSeed seed = lookupByLineage(lineageTestId);
        if (seed == null) {
            return;
        }
        seed.hadBranchPayoff = true;
        if (seed.seedClass == SeedClass.TRACE_PROBATION) {
            promote(seed, "downstream_branch_hit");
        }
    }

    /**
     * Credit the rolling-corpus seed identified by {@code lineageTestId}
     * for a downstream structured tri-lane candidate (Checker D). If the
     * seed is currently in probation, this promotes it.
     */
    public synchronized void notifyStructuredCandidatePayoff(
            int lineageTestId) {
        RollingSeed seed = lookupByLineage(lineageTestId);
        if (seed == null) {
            return;
        }
        seed.hadStructuredCandidatePayoff = true;
        if (seed.seedClass == SeedClass.TRACE_PROBATION) {
            promote(seed, "downstream_structured_candidate");
        }
    }

    // === Eviction ===

    /**
     * Evict trace-probation seeds that have timed out or exhausted their
     * selection budget without any payoff. Intended to be called
     * periodically from the fuzzing server (e.g. once per completed
     * differential round). Returns the number of seeds evicted.
     */
    public synchronized int evictExpiredProbation(long round) {
        advanceRound(round);
        int evicted = 0;
        Iterator<RollingSeed> it = traceProbationPool.iterator();
        while (it.hasNext()) {
            RollingSeed s = it.next();
            if (shouldEvictProbation(s, round)) {
                it.remove();
                if (s.lineageTestId >= 0) {
                    seedByLineage.remove(s.lineageTestId);
                }
                // Remove from the content-hash index if present.
                probationByContentHash.values()
                        .removeIf(candidate -> candidate == s);
                evicted++;
                totalTraceProbationEvicted++;
            }
        }
        return evicted;
    }

    private boolean shouldEvictProbation(RollingSeed s, long round) {
        if (s.hadBranchPayoff || s.hadStructuredCandidatePayoff) {
            return false;
        }
        long age = round - s.creationRound;
        if (age >= traceOnlyProbationRounds) {
            return true;
        }
        if (s.timesSelectedAsParent >= traceProbationMaxSelectionsWithoutPayoff) {
            return true;
        }
        return false;
    }

    // === Parent selection ===

    public synchronized RollingSeed getSeed() {
        double roll = selectionRand.nextDouble();
        return getSeedInternal(roll);
    }

    /**
     * Package-private variant that takes an explicit roll so unit tests can
     * assert the weighting deterministically.
     */
    synchronized RollingSeed getSeedForTest(double roll) {
        return getSeedInternal(roll);
    }

    private RollingSeed getSeedInternal(double roll) {
        boolean preferBranch = roll < branchBackedSelectionWeight;
        if (preferBranch) {
            RollingSeed picked = pickCycle(branchBackedPool,
                    SeedClass.BRANCH_BACKED);
            if (picked != null) {
                return picked;
            }
            picked = pickCycle(tracePromotedPool, SeedClass.TRACE_PROMOTED);
            if (picked != null) {
                return picked;
            }
        } else {
            RollingSeed picked = pickCycle(tracePromotedPool,
                    SeedClass.TRACE_PROMOTED);
            if (picked != null) {
                return picked;
            }
            picked = pickCycle(branchBackedPool, SeedClass.BRANCH_BACKED);
            if (picked != null) {
                return picked;
            }
        }
        // Last resort: probation pool. Keeping probation in the selection
        // set lets newly admitted trace-only seeds earn promotion via
        // downstream signals without requiring another independent
        // rediscovery.
        return pickCycle(traceProbationPool, SeedClass.TRACE_PROBATION);
    }

    private RollingSeed pickCycle(LinkedList<RollingSeed> pool,
            SeedClass clazz) {
        if (pool.isEmpty()) {
            return null;
        }
        int cursor;
        switch (clazz) {
        case BRANCH_BACKED:
            cursor = branchBackedCursor % pool.size();
            branchBackedCursor = (cursor + 1) % pool.size();
            break;
        case TRACE_PROMOTED:
            cursor = tracePromotedCursor % pool.size();
            tracePromotedCursor = (cursor + 1) % pool.size();
            break;
        case TRACE_PROBATION:
            cursor = traceProbationCursor % pool.size();
            traceProbationCursor = (cursor + 1) % pool.size();
            break;
        default:
            cursor = 0;
        }
        RollingSeed picked = pool.get(cursor);
        picked.timesSelectedAsParent++;
        return picked;
    }

    // === Internal helpers ===

    private void promote(RollingSeed seed, String reason) {
        if (seed.seedClass != SeedClass.TRACE_PROBATION) {
            return;
        }
        traceProbationPool.remove(seed);
        // Release the content-hash slot so later identical trace-only
        // admissions are evaluated as fresh candidates instead of being
        // silently swallowed as stale rediscoveries. Rediscovery-driven
        // dedup is Phase 4's responsibility, not Phase 2's — Phase 2
        // rediscovery exists only as a promotion signal while the
        // original seed is still in probation.
        String contentHash = contentHashOf(seed);
        if (contentHash != null
                && probationByContentHash.get(contentHash) == seed) {
            probationByContentHash.remove(contentHash);
        }
        seed.seedClass = SeedClass.TRACE_PROMOTED;
        tracePromotedPool.add(seed);
        totalTracePromoted++;
        logger.info(
                "[RollingCorpus] Promoted trace-probation seed {} ({}): "
                        + "branchBacked={}, probation={}, promoted={}",
                seed.lineageTestId,
                reason,
                branchBackedPool.size(),
                traceProbationPool.size(),
                tracePromotedPool.size());
    }

    private RollingSeed lookupByLineage(int lineageTestId) {
        if (lineageTestId < 0) {
            return null;
        }
        return seedByLineage.get(lineageTestId);
    }

    private void advanceRound(long round) {
        if (round < 0) {
            return;
        }
        if (round != currentRound) {
            currentRound = round;
            traceOnlyAddsInCurrentRound = 0;
        }
    }

    private void pruneOldAdditionRounds(long round) {
        while (!recentTraceOnlyAdditionRounds.isEmpty()
                && round - recentTraceOnlyAdditionRounds.peekFirst() >= 100) {
            recentTraceOnlyAdditionRounds.pollFirst();
        }
    }

    /**
     * Compute a stable, cheap content hash for a seed's command sequences.
     * Uses both the original write sequence and the validation read
     * sequence so functionally different plans with identical writes do
     * not collide.
     */
    static String contentHashOf(RollingSeed rs) {
        if (rs == null || rs.seed == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendCommandSequence(sb, rs.seed.originalCommandSequence);
        sb.append("||");
        appendCommandSequence(sb, rs.seed.validationCommandSequence);
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static void appendCommandSequence(StringBuilder sb,
            CommandSequence cs) {
        if (cs == null || cs.commands == null) {
            sb.append("null");
            return;
        }
        for (Command command : cs.commands) {
            sb.append(command.constructCommandString()).append('\n');
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    // === Accessors for status line and tests ===

    public synchronized boolean isEmpty() {
        return branchBackedPool.isEmpty() && traceProbationPool.isEmpty()
                && tracePromotedPool.isEmpty();
    }

    public synchronized int size() {
        return branchBackedPool.size() + traceProbationPool.size()
                + tracePromotedPool.size();
    }

    public synchronized int branchBackedSize() {
        return branchBackedPool.size();
    }

    public synchronized int traceProbationSize() {
        return traceProbationPool.size();
    }

    public synchronized int tracePromotedSize() {
        return tracePromotedPool.size();
    }

    public synchronized long getTotalBranchBackedAdds() {
        return totalBranchBackedAdds;
    }

    public synchronized long getTotalTraceProbationAdds() {
        return totalTraceProbationAdds;
    }

    public synchronized long getTotalTracePromoted() {
        return totalTracePromoted;
    }

    public synchronized long getTotalTraceRediscoveries() {
        return totalTraceRediscoveries;
    }

    public synchronized long getTotalTraceRejectedPerRound() {
        return totalTraceRejectedPerRound;
    }

    public synchronized long getTotalTraceRejectedWindow() {
        return totalTraceRejectedWindow;
    }

    public synchronized long getTotalTraceRejectedShare() {
        return totalTraceRejectedShare;
    }

    public synchronized long getTotalTraceProbationEvicted() {
        return totalTraceProbationEvicted;
    }

    /**
     * Read-only snapshot of the branch-backed pool (test-only helper).
     */
    synchronized List<RollingSeed> snapshotBranchBacked() {
        return Collections.unmodifiableList(new LinkedList<>(branchBackedPool));
    }

    /**
     * Read-only snapshot of the trace-probation pool (test-only helper).
     */
    synchronized List<RollingSeed> snapshotTraceProbation() {
        return Collections.unmodifiableList(
                new LinkedList<>(traceProbationPool));
    }

    /**
     * Read-only snapshot of the trace-promoted pool (test-only helper).
     */
    synchronized List<RollingSeed> snapshotTracePromoted() {
        return Collections.unmodifiableList(
                new LinkedList<>(tracePromotedPool));
    }
}
