package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.Command;
import org.zlab.upfuzz.CommandSequence;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;

/**
 * Phase 2 regression tests for {@link RollingSeedCorpus}.
 *
 * <p>The Apr 12 mode-5 campaign showed the rolling corpus saturated with
 * trace-only seeds (0.85 save rate, 1959 trace-only adds) while a branch-
 * novel plan in the Cassandra 4.1.10 → 5.0.6 equal-round comparison was
 * crowded out. Phase 2 introduces a tiered pool model with admission caps,
 * a probation period for trace-only seeds, and weighted parent selection.
 * These tests lock that behavior in:
 *
 * <ul>
 *   <li>branch-backed admissions are unconditional;</li>
 *   <li>trace-only admissions go into probation;</li>
 *   <li>trace-only admissions respect per-round, 100-round, and share
 *       caps — and the caps do not affect branch-backed seeds;</li>
 *   <li>rediscovery, downstream branch hits, and downstream structured
 *       candidates each promote a probation seed to the promoted pool;</li>
 *   <li>eviction removes probation seeds that time out or exhaust their
 *       selection budget without any payoff;</li>
 *   <li>weighted parent selection honors the configured split and falls
 *       back gracefully when a pool is empty.</li>
 * </ul>
 */
class RollingSeedCorpusPhase2Test {

    // ------------------------------------------------------------------
    // Admission
    // ------------------------------------------------------------------

    @Test
    void branchBackedAdmissionsIgnoreTraceOnlyCaps() {
        // Trace-only caps must never block branch-backed admissions. We
        // configure degenerate trace-only caps (0 per round, 0 per 100
        // rounds, 0 share) and still expect every branch-backed seed to be
        // accepted. This is the "branch-backed seeds are never rejected
        // because trace pool is full" invariant the Apr 12 plan calls for.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 8,
                /*share*/ 0.0,
                /*perRound*/ 0,
                /*per100*/ 0,
                /*probationRounds*/ 50,
                /*maxSelections*/ 5,
                /*rediscoveryThreshold*/ 3,
                /*weight*/ 0.5);

        for (int i = 0; i < 10; i++) {
            RollingSeed seed = makeUniqueSeed(100 + i);
            assertEquals(RollingSeedCorpus.AdmissionOutcome.BRANCH_BACKED,
                    corpus.tryAdmit(seed, AdmissionReason.BRANCH_ONLY, i));
        }

        assertEquals(10, corpus.branchBackedSize());
        assertEquals(0, corpus.traceProbationSize());
        assertEquals(0, corpus.tracePromotedSize());
        assertEquals(10, corpus.getTotalBranchBackedAdds());
    }

    @Test
    void traceOnlyAdmissionsGoIntoProbation() {
        RollingSeedCorpus corpus = defaultCorpus();
        RollingSeed seed = makeUniqueSeed(200);

        RollingSeedCorpus.AdmissionOutcome outcome = corpus.tryAdmit(seed,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0);

        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                outcome);
        assertEquals(SeedClass.TRACE_PROBATION, seed.seedClass);
        assertEquals(1, corpus.traceProbationSize());
        assertEquals(0, corpus.branchBackedSize());
        assertEquals(0, corpus.tracePromotedSize());
    }

    @Test
    void traceOnlyPerRoundCapLimitsAdmissionsWithinSingleRound() {
        // perRound=1. Two trace-only admissions within the same round must
        // produce one success and one rejection. The rejection must set a
        // PER_ROUND_CAP outcome so the caller can stop adding the plan to
        // the short-term testPlanCorpus as well.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 100, /*share*/ 1.0,
                /*perRound*/ 1, /*per100*/ 100,
                /*probationRounds*/ 50, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 3, /*weight*/ 0.5);

        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(300),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 5));
        assertEquals(
                RollingSeedCorpus.AdmissionOutcome.REJECTED_PER_ROUND_CAP,
                corpus.tryAdmit(makeUniqueSeed(301),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 5));
        // Rejected admissions do not consume a probation slot.
        assertEquals(1, corpus.traceProbationSize());
        assertEquals(1, corpus.getTotalTraceRejectedPerRound());

        // Moving to the next round unlocks a new per-round budget.
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(302),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 6));
        assertEquals(2, corpus.traceProbationSize());
    }

    @Test
    void traceOnly100RoundCapLimitsAdmissionsOverWindow() {
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 100, /*share*/ 1.0,
                /*perRound*/ 5, /*per100*/ 3,
                /*probationRounds*/ 200, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 5, /*weight*/ 0.5);

        // Rounds 0..2: accepted
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(400),
                        AdmissionReason.TRACE_ONLY_WINDOW_SIM, 0));
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(401),
                        AdmissionReason.TRACE_ONLY_WINDOW_SIM, 1));
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(402),
                        AdmissionReason.TRACE_ONLY_WINDOW_SIM, 2));
        // Round 3: still within the 100-round window, now cap-limited.
        assertEquals(RollingSeedCorpus.AdmissionOutcome.REJECTED_WINDOW_CAP,
                corpus.tryAdmit(makeUniqueSeed(403),
                        AdmissionReason.TRACE_ONLY_WINDOW_SIM, 3));
        // Round 102: the first addition (round 0) has aged out of the
        // 100-round window. Space opens up again.
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(404),
                        AdmissionReason.TRACE_ONLY_WINDOW_SIM, 102));
        assertEquals(4, corpus.traceProbationSize());
        assertEquals(1, corpus.getTotalTraceRejectedWindow());
    }

    @Test
    void traceOnlyShareCapBlocksAdmissionsWhenPoolIsFull() {
        // Share cap = floor(max * share). With max=10 and share=0.2 the
        // trace-only pool is limited to 2 seeds.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 10, /*share*/ 0.2,
                /*perRound*/ 10, /*per100*/ 10,
                /*probationRounds*/ 200, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 5, /*weight*/ 0.5);

        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(500),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0));
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(makeUniqueSeed(501),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 1));
        assertEquals(RollingSeedCorpus.AdmissionOutcome.REJECTED_SHARE_CAP,
                corpus.tryAdmit(makeUniqueSeed(502),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 2));
        // Branch-backed seeds are still accepted even when trace-only is
        // at its share cap — the "reserve at least half for branch-backed"
        // invariant.
        assertEquals(RollingSeedCorpus.AdmissionOutcome.BRANCH_BACKED,
                corpus.tryAdmit(makeUniqueSeed(503),
                        AdmissionReason.BRANCH_ONLY, 2));
        assertEquals(2, corpus.traceProbationSize());
        assertEquals(1, corpus.branchBackedSize());
        assertEquals(1, corpus.getTotalTraceRejectedShare());
    }

    // ------------------------------------------------------------------
    // Promotion
    // ------------------------------------------------------------------

    @Test
    void downstreamBranchHitPromotesProbationSeed() {
        RollingSeedCorpus corpus = defaultCorpus();
        RollingSeed seed = makeUniqueSeed(600);
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(seed,
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0));
        assertEquals(1, corpus.traceProbationSize());

        corpus.notifyBranchPayoff(600);

        assertEquals(SeedClass.TRACE_PROMOTED, seed.seedClass);
        assertEquals(0, corpus.traceProbationSize());
        assertEquals(1, corpus.tracePromotedSize());
        assertTrue(seed.hadBranchPayoff);
        assertEquals(1, corpus.getTotalTracePromoted());
    }

    @Test
    void downstreamStructuredCandidateHitPromotesProbationSeed() {
        RollingSeedCorpus corpus = defaultCorpus();
        RollingSeed seed = makeUniqueSeed(700);
        corpus.tryAdmit(seed, AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE,
                0);

        corpus.notifyStructuredCandidatePayoff(700);

        assertEquals(SeedClass.TRACE_PROMOTED, seed.seedClass);
        assertEquals(1, corpus.tracePromotedSize());
        assertTrue(seed.hadStructuredCandidatePayoff);
    }

    @Test
    void rediscoveryFromIndependentParentsPromotesAfterThreshold() {
        // Threshold = 2. Two rediscoveries FROM DISTINCT parent lineage
        // roots must promote the original probation seed. Rediscoveries
        // do not themselves consume a probation slot and are tracked
        // separately from caps.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 1, /*per100*/ 100,
                /*probationRounds*/ 200, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 2, /*weight*/ 0.5);

        RollingSeed first = makeSeedWithCommand(800, "INSERT x");
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(first,
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0,
                        /*parentRoot*/ -1));

        // Second admission with identical content from a distinct parent
        // root → rediscovered, counter=1.
        RollingSeed rediscovered1 = makeSeedWithCommand(801, "INSERT x");
        assertEquals(RollingSeedCorpus.AdmissionOutcome.REDISCOVERED,
                corpus.tryAdmit(rediscovered1,
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 1,
                        /*parentRoot*/ 900));
        assertEquals(SeedClass.TRACE_PROBATION, first.seedClass);
        assertEquals(1, first.rediscoveryCount);

        // Third admission with identical content from another distinct
        // parent root → rediscovered, counter=2, reaches threshold,
        // promotes.
        RollingSeed rediscovered2 = makeSeedWithCommand(802, "INSERT x");
        assertEquals(RollingSeedCorpus.AdmissionOutcome.REDISCOVERED,
                corpus.tryAdmit(rediscovered2,
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 2,
                        /*parentRoot*/ 901));
        assertEquals(SeedClass.TRACE_PROMOTED, first.seedClass);
        assertEquals(2, first.rediscoveryCount);
        assertEquals(1, corpus.tracePromotedSize());
        assertEquals(0, corpus.traceProbationSize());
        assertEquals(2, corpus.getTotalTraceRediscoveries());
    }

    @Test
    void rediscoveriesFromSameParentDoNotCountTwice() {
        // Apr 12 plan requires *independent* parents. Two rediscoveries
        // that share the same parent lineage root must count as a single
        // event. With threshold=2 and all rediscoveries coming from the
        // same parent, the seed must NOT be promoted.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 1, /*per100*/ 100,
                /*probationRounds*/ 200, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 2, /*weight*/ 0.5);

        RollingSeed first = makeSeedWithCommand(1900, "UPDATE repeat");
        corpus.tryAdmit(first, AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE,
                0, /*parentRoot*/ -1);

        // Two rediscoveries from the same parent root — the second must
        // be a no-op for the counter.
        assertEquals(RollingSeedCorpus.AdmissionOutcome.REDISCOVERED,
                corpus.tryAdmit(makeSeedWithCommand(1901, "UPDATE repeat"),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 1,
                        /*parentRoot*/ 500));
        assertEquals(1, first.rediscoveryCount);
        assertEquals(SeedClass.TRACE_PROBATION, first.seedClass);

        assertEquals(RollingSeedCorpus.AdmissionOutcome.REDISCOVERED,
                corpus.tryAdmit(makeSeedWithCommand(1902, "UPDATE repeat"),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 2,
                        /*parentRoot*/ 500));
        assertEquals(1, first.rediscoveryCount,
                "repeat rediscovery from same parent must not count");
        assertEquals(SeedClass.TRACE_PROBATION, first.seedClass);

        // But a third rediscovery from a *distinct* parent root should
        // then bump the counter and promote.
        assertEquals(RollingSeedCorpus.AdmissionOutcome.REDISCOVERED,
                corpus.tryAdmit(makeSeedWithCommand(1903, "UPDATE repeat"),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 3,
                        /*parentRoot*/ 501));
        assertEquals(2, first.rediscoveryCount);
        assertEquals(SeedClass.TRACE_PROMOTED, first.seedClass);
    }

    @Test
    void rediscoveryFromSelfParentDoesNotCount() {
        // A mutation loop that lands back on the same content — the
        // probation seed being its own "ancestor" — must not count as a
        // rediscovery. Repeated self-emissions from a single seed do not
        // indicate independent exploration.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 1, /*per100*/ 100,
                /*probationRounds*/ 200, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 1, /*weight*/ 0.5);

        RollingSeed first = makeSeedWithCommand(2000, "DELETE self");
        corpus.tryAdmit(first, AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE,
                0, /*parentRoot*/ -1);
        // first.lineageTestId is 2000.

        // Admission with same content whose parent root IS the probation
        // seed itself — must not count even though threshold=1 would
        // otherwise promote on a single rediscovery.
        assertEquals(RollingSeedCorpus.AdmissionOutcome.REDISCOVERED,
                corpus.tryAdmit(makeSeedWithCommand(2001, "DELETE self"),
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 1,
                        /*parentRoot*/ 2000));
        assertEquals(0, first.rediscoveryCount);
        assertEquals(SeedClass.TRACE_PROBATION, first.seedClass);
        assertEquals(0, corpus.getTotalTraceRediscoveries());
    }

    @Test
    void postPromotionIdenticalAdmissionIsFreshNotStaleRediscovery() {
        // After a probation seed is promoted, its content-hash slot must
        // be released so later identical trace-only admissions are
        // evaluated as fresh candidates. Rediscovery-driven dedup is
        // Phase 4's responsibility, not Phase 2's — swallowing duplicate
        // admissions after promotion would amount to hidden Phase-4
        // behavior landing in Phase 2.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 10, /*per100*/ 100,
                /*probationRounds*/ 200, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 2, /*weight*/ 0.5);

        RollingSeed original = makeSeedWithCommand(2100, "MERGE both");
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                corpus.tryAdmit(original,
                        AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0,
                        /*parentRoot*/ -1));
        assertEquals(1, corpus.traceProbationSize());

        // Promote via branch payoff.
        corpus.notifyBranchPayoff(2100);
        assertEquals(SeedClass.TRACE_PROMOTED, original.seedClass);
        assertEquals(1, corpus.tracePromotedSize());
        assertEquals(0, corpus.traceProbationSize());

        // A later identical trace-only admission must take the fresh
        // admission path (→ TRACE_PROBATION), not be silently swallowed
        // as a stale rediscovery. The original's rediscovery counter
        // must not move.
        RollingSeed duplicate = makeSeedWithCommand(2101, "MERGE both");
        RollingSeedCorpus.AdmissionOutcome outcome = corpus.tryAdmit(
                duplicate,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 1,
                /*parentRoot*/ 700);
        assertEquals(RollingSeedCorpus.AdmissionOutcome.TRACE_PROBATION,
                outcome,
                "post-promotion identical admission must be fresh, not stale rediscovery");
        assertEquals(1, corpus.traceProbationSize());
        assertEquals(1, corpus.tracePromotedSize());
        assertEquals(0, original.rediscoveryCount);
        assertEquals(0, corpus.getTotalTraceRediscoveries());
        // The new probation seed should be the duplicate, with its own
        // lineage id.
        assertSame(duplicate,
                corpus.snapshotTraceProbation().get(0));
        assertEquals(SeedClass.TRACE_PROBATION, duplicate.seedClass);
    }

    // ------------------------------------------------------------------
    // Eviction
    // ------------------------------------------------------------------

    @Test
    void probationSeedIsEvictedAfterTimeout() {
        // probationRounds=5. A seed admitted at round 0 must be evicted
        // once evictExpiredProbation is called with round >= 5 and no
        // payoff has been credited. Branch-backed seeds must not be
        // touched by eviction.
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 2, /*per100*/ 100,
                /*probationRounds*/ 5, /*maxSelections*/ 100,
                /*rediscoveryThreshold*/ 5, /*weight*/ 0.5);

        RollingSeed branchSeed = makeUniqueSeed(900);
        corpus.tryAdmit(branchSeed, AdmissionReason.BRANCH_ONLY, 0);
        RollingSeed traceSeed = makeUniqueSeed(901);
        corpus.tryAdmit(traceSeed,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0);
        assertEquals(1, corpus.branchBackedSize());
        assertEquals(1, corpus.traceProbationSize());

        // Round 4: still within the probation window.
        assertEquals(0, corpus.evictExpiredProbation(4));
        assertEquals(1, corpus.traceProbationSize());

        // Round 5: probation expired — evict.
        assertEquals(1, corpus.evictExpiredProbation(5));
        assertEquals(0, corpus.traceProbationSize());
        // Branch-backed pool must not be touched.
        assertEquals(1, corpus.branchBackedSize());
        assertEquals(1, corpus.getTotalTraceProbationEvicted());
    }

    @Test
    void probationSeedIsEvictedAfterTooManySelectionsWithoutPayoff() {
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 2, /*per100*/ 100,
                /*probationRounds*/ 500, /*maxSelections*/ 3,
                /*rediscoveryThreshold*/ 5, /*weight*/ 1.0);

        // weight=1.0 forces getSeed to always try branch-backed first,
        // falling through to probation only when branch-backed is empty.
        // This gives us a deterministic way to repeatedly select the
        // probation seed.
        RollingSeed traceSeed = makeUniqueSeed(1000);
        corpus.tryAdmit(traceSeed,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0);
        assertEquals(1, corpus.traceProbationSize());

        // Select three times — exactly the budget. Selection bumps
        // timesSelectedAsParent on the RollingSeed.
        for (int i = 0; i < 3; i++) {
            RollingSeed picked = corpus.getSeedForTest(0.99);
            assertSame(traceSeed, picked);
        }
        assertEquals(3, traceSeed.timesSelectedAsParent);

        // No payoff has been credited, so the selection budget should
        // now trigger eviction.
        assertEquals(1, corpus.evictExpiredProbation(1));
        assertEquals(0, corpus.traceProbationSize());
    }

    @Test
    void paidOffProbationSeedIsNotEvicted() {
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 2, /*per100*/ 100,
                /*probationRounds*/ 3, /*maxSelections*/ 1,
                /*rediscoveryThreshold*/ 5, /*weight*/ 1.0);

        RollingSeed seed = makeUniqueSeed(1100);
        corpus.tryAdmit(seed, AdmissionReason.TRACE_ONLY_WINDOW_SIM, 0);
        corpus.notifyBranchPayoff(1100); // promotes to TRACE_PROMOTED
        assertEquals(SeedClass.TRACE_PROMOTED, seed.seedClass);

        // Eviction only looks at the probation pool, so a promoted seed
        // with a paid-off flag survives any subsequent eviction pass.
        assertEquals(0, corpus.evictExpiredProbation(100));
        assertEquals(1, corpus.tracePromotedSize());
        assertEquals(0, corpus.traceProbationSize());
    }

    // ------------------------------------------------------------------
    // Weighted parent selection
    // ------------------------------------------------------------------

    @Test
    void weightedSelectionPrefersBranchBackedWhenRollIsBelowWeight() {
        RollingSeedCorpus corpus = buildCorpus(
                /*max*/ 20, /*share*/ 0.5,
                /*perRound*/ 10, /*per100*/ 100,
                /*probationRounds*/ 200, /*maxSelections*/ 100,
                /*rediscoveryThreshold*/ 5, /*weight*/ 0.5);

        RollingSeed branch = makeUniqueSeed(1200);
        corpus.tryAdmit(branch, AdmissionReason.BRANCH_ONLY, 0);
        RollingSeed promoted = makeUniqueSeed(1201);
        corpus.tryAdmit(promoted,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0);
        corpus.notifyBranchPayoff(1201);
        assertEquals(SeedClass.TRACE_PROMOTED, promoted.seedClass);

        // roll=0.0 < weight=0.5 → prefer branch-backed
        assertSame(branch, corpus.getSeedForTest(0.0));
        // roll=0.49 < 0.5 → prefer branch-backed
        assertSame(branch, corpus.getSeedForTest(0.49));
        // roll=0.5 >= 0.5 → prefer promoted trace
        assertSame(promoted, corpus.getSeedForTest(0.5));
        // roll=0.99 >= 0.5 → prefer promoted trace
        assertSame(promoted, corpus.getSeedForTest(0.99));
    }

    @Test
    void weightedSelectionFallsThroughToPromotedWhenBranchEmpty() {
        RollingSeedCorpus corpus = defaultCorpus();
        RollingSeed promoted = makeUniqueSeed(1300);
        corpus.tryAdmit(promoted,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0);
        corpus.notifyBranchPayoff(1300);

        // roll=0.0 prefers branch-backed, which is empty → pick promoted.
        assertSame(promoted, corpus.getSeedForTest(0.0));
    }

    @Test
    void weightedSelectionFallsThroughToProbationWhenLongLivedPoolsEmpty() {
        // Neither branch-backed nor promoted-trace have any seeds.
        // Probation is the only pool with content. Selection must fall
        // through to probation so newly admitted trace-only seeds still
        // get a chance to exercise and earn promotion.
        RollingSeedCorpus corpus = defaultCorpus();
        RollingSeed probation = makeUniqueSeed(1400);
        corpus.tryAdmit(probation,
                AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, 0);

        assertSame(probation, corpus.getSeedForTest(0.0));
        assertSame(probation, corpus.getSeedForTest(0.99));
    }

    @Test
    void weightedSelectionRoundRobinsWithinPool() {
        RollingSeedCorpus corpus = defaultCorpus();
        RollingSeed a = makeUniqueSeed(1500);
        RollingSeed b = makeUniqueSeed(1501);
        RollingSeed c = makeUniqueSeed(1502);
        corpus.tryAdmit(a, AdmissionReason.BRANCH_ONLY, 0);
        corpus.tryAdmit(b, AdmissionReason.BRANCH_ONLY, 0);
        corpus.tryAdmit(c, AdmissionReason.BRANCH_ONLY, 0);

        // All branch-backed, so roll<0.5 always picks branch-backed and
        // cycles through a, b, c deterministically.
        assertSame(a, corpus.getSeedForTest(0.0));
        assertSame(b, corpus.getSeedForTest(0.0));
        assertSame(c, corpus.getSeedForTest(0.0));
        assertSame(a, corpus.getSeedForTest(0.0));
    }

    // ------------------------------------------------------------------
    // Miscellaneous
    // ------------------------------------------------------------------

    @Test
    void legacyAddSeedPathBehavesLikeBranchBackedAdmission() {
        // The mode-5 bootstrap path and any legacy call site that predates
        // Phase 2 still uses rollingSeedCorpus.addSeed(seed). That entry
        // point must continue to work and treat the seed as branch-backed
        // so it is never evicted.
        RollingSeedCorpus corpus = defaultCorpus();
        RollingSeed seed = makeUniqueSeed(1600);
        corpus.addSeed(seed);
        assertEquals(SeedClass.BRANCH_BACKED, seed.seedClass);
        assertEquals(1, corpus.branchBackedSize());
        assertFalse(corpus.isEmpty());
    }

    @Test
    void emptyCorpusReturnsNullFromGetSeed() {
        RollingSeedCorpus corpus = defaultCorpus();
        assertNull(corpus.getSeedForTest(0.0));
        assertNull(corpus.getSeedForTest(0.99));
        assertTrue(corpus.isEmpty());
    }

    @Test
    void contentHashMatchesAcrossIdenticalCommandSequences() {
        // Ensures the content-hash helper treats two seeds with the same
        // command strings as identical. The rediscovery path depends on
        // this invariant.
        RollingSeed a = makeSeedWithCommand(1700, "UPDATE foo SET x=1");
        RollingSeed b = makeSeedWithCommand(1701, "UPDATE foo SET x=1");
        RollingSeed c = makeSeedWithCommand(1702, "UPDATE foo SET x=2");
        assertEquals(RollingSeedCorpus.contentHashOf(a),
                RollingSeedCorpus.contentHashOf(b));
        assertNotNull(RollingSeedCorpus.contentHashOf(a));
        // Different content → different hash.
        assertFalse(RollingSeedCorpus.contentHashOf(a)
                .equals(RollingSeedCorpus.contentHashOf(c)));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static RollingSeedCorpus defaultCorpus() {
        return buildCorpus(
                /*max*/ 50, /*share*/ 0.5,
                /*perRound*/ 10, /*per100*/ 100,
                /*probationRounds*/ 50, /*maxSelections*/ 10,
                /*rediscoveryThreshold*/ 3, /*weight*/ 0.5);
    }

    private static RollingSeedCorpus buildCorpus(
            int max, double share,
            int perRound, int per100,
            int probationRounds, int maxSelections,
            int rediscoveryThreshold, double weight) {
        return new RollingSeedCorpus(max, share, perRound, per100,
                probationRounds, maxSelections, rediscoveryThreshold, weight,
                new Random(42L));
    }

    /**
     * Build a minimal RollingSeed with a distinct lineage id. The
     * underlying Seed has null CommandSequences, which the content hash
     * helper treats as "empty" — so two unique seeds built this way will
     * collide on content. For tests that care about rediscovery the
     * {@link #makeSeedWithCommand(int, String)} helper is preferred.
     */
    private static RollingSeed makeUniqueSeed(int lineageId) {
        Seed seed = new Seed(
                sequenceWithCommand("op_" + lineageId),
                sequenceWithCommand("read_" + lineageId),
                /*configIdx*/ 0,
                /*testID*/ lineageId,
                /*mutationDepth*/ 0);
        RollingSeed rs = new RollingSeed(seed, new LinkedList<>());
        rs.lineageTestId = lineageId;
        return rs;
    }

    /**
     * Build a RollingSeed whose content hash is determined by a single
     * stub command string. Two seeds built with the same string share
     * a content hash (required for the rediscovery test). The lineage id
     * is distinct across calls so admission/promotion bookkeeping still
     * works.
     */
    private static RollingSeed makeSeedWithCommand(int lineageId,
            String commandString) {
        Seed seed = new Seed(
                sequenceWithCommand(commandString),
                sequenceWithCommand(commandString),
                /*configIdx*/ 0,
                /*testID*/ lineageId,
                /*mutationDepth*/ 0);
        RollingSeed rs = new RollingSeed(seed, new LinkedList<>());
        rs.lineageTestId = lineageId;
        return rs;
    }

    private static CommandSequence sequenceWithCommand(String commandString) {
        LinkedList<Command> commands = new LinkedList<>();
        commands.add(new StubCommand(commandString));
        return new CommandSequence(commands, null, null, null, null);
    }

    /**
     * Minimal Command stub used only for building test RollingSeeds with
     * predictable content hashes. The three abstract Command methods are
     * all no-ops except {@link #constructCommandString()}, which returns
     * the stored string so the content hash is deterministic.
     */
    private static final class StubCommand extends Command {
        private static final long serialVersionUID = 1L;
        private final String str;

        StubCommand(String str) {
            super();
            this.str = str;
        }

        @Override
        public String constructCommandString() {
            return str;
        }

        @Override
        public void updateState(State state) {
        }

        @Override
        public void separate(State state) {
        }
    }
}
