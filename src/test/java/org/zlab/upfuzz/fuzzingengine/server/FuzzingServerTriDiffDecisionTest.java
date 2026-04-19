package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.zlab.net.tracker.SendMeta;
import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.diff.DiffComputeMessageTriDiff;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.FuzzingServer.TraceStrengthGates;
import org.zlab.upfuzz.fuzzingengine.server.FuzzingServer.TriDiffWindowDecision;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceMetadataCoverageRow;
import org.zlab.upfuzz.fuzzingengine.trace.BoundaryResolutionResult;
import org.zlab.upfuzz.fuzzingengine.trace.ResolvedTraceEndpoint;
import org.zlab.upfuzz.fuzzingengine.trace.TopologyNormalizer;
import org.zlab.upfuzz.fuzzingengine.trace.TopologySnapshot;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;

/**
 * Phase 1 regression tests for {@link FuzzingServer#evaluateTriDiffWindow}.
 *
 * <p>The Apr 12 campaign showed that missing-message churn was driving most
 * mode-5 admissions even though the messages that were "missing" from the
 * rolling lane reflected benign rolling-upgrade transitions, not real bugs.
 * Phase 1 removes missing-only direct admission. These tests lock that
 * behavior in:
 *
 * <ul>
 * <li>a pure missing-only window does not produce {@code triDiffInteresting=true};
 * <li>a rolling-exclusive window still produces {@code triDiffInteresting=true};
 * <li>{@code PRE_UPGRADE} missing churn is suppressed even for the observability
 * counter so the CSV view matches the admission view;
 * <li>{@code POST_STAGE} missing churn is reported for observability but still
 * does not contribute to admission.
 * </ul>
 */
class FuzzingServerTriDiffDecisionTest {

    private static final int EXCLUSIVE_MIN_COUNT = 3;
    private static final double EXCLUSIVE_MIN_FRACTION = 0.05;
    private static final int MISSING_MIN_COUNT = 3;
    private static final double MISSING_MIN_FRACTION = 0.05;

    @Test
    void missingOnlyWindowDoesNotAdmit() {
        // Both baselines share m1..m10, rolling dropped m3..m10 (8 missing,
        // fraction = 0.8). Under the pre-Phase-1 rule this would have fired
        // triDiffMissingFired and set traceInteresting=true. After Phase 1 the
        // missing signal is still visible for observability but must not
        // contribute to triDiffInteresting.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9",
                        "m10"),
                traceOf("m1", "m2"),
                traceOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9",
                        "m10"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION);

        assertEquals(0, triDiff.rollingExclusiveCount());
        assertEquals(8, triDiff.rollingMissingCount());
        assertTrue(triDiff.rollingMissingFraction() > 0.5);
        assertTrue(triDiff.rollingMissingFraction() <= 1.0);
        assertTrue(decision.missingInteresting,
                "missing counter should still fire for post-upgrade observability");
        assertFalse(decision.exclusiveInteresting);
        assertFalse(decision.triDiffInteresting,
                "Phase 1 hotfix: missing-only windows must not admit seeds");
    }

    @Test
    void rollingExclusiveWindowAdmits() {
        // Rolling lane introduces 5 messages neither baseline has. This is the
        // "real divergence" pattern Phase 1 is designed to preserve.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5", "rolling_only_1",
                        "rolling_only_2", "rolling_only_3", "rolling_only_4",
                        "rolling_only_5"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION);

        assertEquals(5, triDiff.rollingExclusiveCount());
        assertEquals(0, triDiff.rollingMissingCount());
        assertTrue(triDiff.rollingExclusiveFraction() > 0.05);
        assertTrue(triDiff.rollingExclusiveFraction() <= 1.0);
        assertTrue(decision.exclusiveInteresting);
        assertFalse(decision.missingInteresting);
        assertTrue(decision.triDiffInteresting,
                "rolling-exclusive signal must remain the direct admission path");
    }

    @Test
    void preUpgradeMissingChurnIsSuppressed() {
        // Same "missing-only" pattern as the first test, but tagged as
        // PRE_UPGRADE. Apr 12 evidence showed PRE_UPGRADE missing drift was
        // effectively always-on because it reflected gossip churn rather than
        // upgrade behavior. The stage gate must zero out missingInteresting
        // entirely so neither admission nor the observability counter fires.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9",
                        "m10"),
                traceOf("m1", "m2"),
                traceOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9",
                        "m10"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.PRE_UPGRADE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION);

        assertFalse(decision.exclusiveInteresting);
        assertFalse(decision.missingInteresting,
                "PRE_UPGRADE windows must never report missingInteresting");
        assertFalse(decision.triDiffInteresting);
    }

    @Test
    void preUpgradeRollingExclusiveStillAdmits() {
        // Stage gate only touches the missing path. Rolling-exclusive churn in
        // PRE_UPGRADE remains an admission signal.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3"),
                traceOf("m1", "m2", "m3", "rolling_only_1", "rolling_only_2",
                        "rolling_only_3"),
                traceOf("m1", "m2", "m3"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.PRE_UPGRADE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION);

        assertTrue(decision.exclusiveInteresting);
        assertFalse(decision.missingInteresting);
        assertTrue(decision.triDiffInteresting);
    }

    @Test
    void belowThresholdExclusiveDoesNotAdmit() {
        // Single exclusive message is below the count threshold (3); the
        // window must not admit even though exclusiveCount > 0.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5", "rolling_only_1"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION);

        assertEquals(1, triDiff.rollingExclusiveCount());
        assertFalse(decision.exclusiveInteresting);
        assertFalse(decision.triDiffInteresting);
    }

    // ---------------------------------------------------------------
    // classifyAdmissionReason — Phase 1 priority regression
    // ---------------------------------------------------------------

    @Test
    void classifierReturnsBranchOnlyWhenOnlyBranchFires() {
        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ true,
                /* traceInteresting */ false,
                /* triDiffExclusiveFired */ false,
                /* windowSimFired */ false,
                /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_ONLY, reason);
    }

    @Test
    void classifierReturnsBranchAndTraceWhenBothFire() {
        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ true,
                /* traceInteresting */ true,
                /* triDiffExclusiveFired */ true,
                /* windowSimFired */ false,
                /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_AND_TRACE, reason);
    }

    @Test
    void classifierPrefersExclusiveOverWindowSim() {
        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ false,
                /* traceInteresting */ true,
                /* triDiffExclusiveFired */ true,
                /* windowSimFired */ true,
                /* aggregateSimFired */ true);
        assertEquals(AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE, reason);
    }

    @Test
    void classifierReportsWindowSimWhenOnlyWindowSimFires() {
        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ false,
                /* traceInteresting */ true,
                /* triDiffExclusiveFired */ false,
                /* windowSimFired */ true,
                /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.TRACE_ONLY_WINDOW_SIM, reason);
    }

    @Test
    void classifierReportsWindowSimWhenOnlyAggregateFires() {
        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ false,
                /* traceInteresting */ true,
                /* triDiffExclusiveFired */ false,
                /* windowSimFired */ false,
                /* aggregateSimFired */ true);
        assertEquals(AdmissionReason.TRACE_ONLY_WINDOW_SIM, reason);
    }

    @Test
    void classifierFallsBackToUnknownWhenNothingFires() {
        // After Phase 1 the caller only invokes classify when addToCorpus is
        // true, so this combination should never be reached in production.
        // Locking it in here keeps the classifier's contract explicit and
        // makes it obvious that "missing co-fired on its own" can never
        // produce TRACE_ONLY_TRIDIFF_MISSING via this entry point.
        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ false,
                /* traceInteresting */ false,
                /* triDiffExclusiveFired */ false,
                /* windowSimFired */ false,
                /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.UNKNOWN, reason);
    }

    @Test
    void classifierNeverReportsTraceOnlyTriDiffMissingUnderPhase1() {
        // Exercise every reachable combination of the five classifier inputs
        // and assert TRACE_ONLY_TRIDIFF_MISSING is impossible. The enum value
        // is kept for historical CSV compatibility but must be dead under
        // Phase 1, because "missing co-fired" is a separate observability
        // signal on AdmissionSummaryRow.triDiffMissingFired rather than a
        // primary admission reason.
        boolean[] bools = new boolean[] { false, true };
        for (boolean branch : bools) {
            for (boolean trace : bools) {
                for (boolean exclusive : bools) {
                    for (boolean windowSim : bools) {
                        for (boolean aggregate : bools) {
                            AdmissionReason reason = FuzzingServer
                                    .classifyAdmissionReason(branch, trace,
                                            exclusive, windowSim, aggregate);
                            assertNotEquals(
                                    AdmissionReason.TRACE_ONLY_TRIDIFF_MISSING,
                                    reason,
                                    "missing-only must not be a primary admission reason");
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Phase 0 support-gate regression
    // ---------------------------------------------------------------

    /**
     * Phase 0 regression: the support gate must require three-way shared
     * support ({@code total_all_three_count > 0}), not merely baseline
     * overlap. {@code baseline_shared_count} in
     * {@link DiffComputeMessageTriDiff} equals
     * {@code totalAllThreeCount + rollingMissingCount}, so the two
     * counters diverge exactly when rolling is the lane that dropped
     * the baseline-shared messages. That is the Apr15 "unsupported
     * window" pattern: old-old and new-new agree on a set of messages
     * but rolling never saw them. A window like that would still look
     * "supported" under a {@code baseline_shared_count > 0} gate.
     */
    @Test
    void supportGateRejectsBaselineSharedWithoutAllThree() {
        // Both baselines share m1..m5; rolling lane is completely
        // disjoint (r1..r3). baseline_shared_count = 5 via the
        // accessor, but total_all_three_count = 0 because rolling has
        // no overlap with either baseline.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("r1", "r2", "r3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        assertEquals(0, triDiff.totalAllThreeCount(),
                "rolling has no overlap with the baselines");
        assertEquals(5, triDiff.baselineSharedCount(),
                "baselineSharedCount counts the baseline-only messages too");
        assertTrue(triDiff.baselineSharedCount() > 0
                && triDiff.totalAllThreeCount() == 0,
                "precondition for the Apr15 unsupported-window pattern");

        // The window-level classifier must return UNSUPPORTED when
        // supportGatePassed is false, regardless of corroborating
        // changed-message or upgraded-boundary signals. This is the
        // label the admission summary row will carry.
        TraceEvidenceStrength strength = FuzzingServer
                .classifyWindowTraceEvidenceStrength(
                        /* windowFired */ true,
                        /* supportGatePassed */ false,
                        TraceWindow.StageKind.POST_STAGE,
                        /* changedMessageCount */ 3,
                        /* upgradedBoundaryEventCount */ 1);
        assertEquals(TraceEvidenceStrength.UNSUPPORTED, strength);
    }

    /**
     * Phase 0 regression: mirror of the above — when
     * {@code total_all_three_count > 0} the classifier can promote
     * past UNSUPPORTED. Corroboration still has to be present to
     * reach STRONG (stage must be mixed-version-relevant and at
     * least one changed-message or upgraded-boundary event).
     */
    @Test
    void supportGateAcceptsWindowsWithThreeWayOverlap() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        assertEquals(3, triDiff.totalAllThreeCount());
        assertTrue(triDiff.totalAllThreeCount() > 0);

        TraceEvidenceStrength strong = FuzzingServer
                .classifyWindowTraceEvidenceStrength(
                        /* windowFired */ true,
                        /* supportGatePassed */ triDiff
                                .totalAllThreeCount() > 0,
                        TraceWindow.StageKind.POST_STAGE,
                        /* changedMessageCount */ 1,
                        /* upgradedBoundaryEventCount */ 1);
        assertEquals(TraceEvidenceStrength.STRONG, strong);

        TraceEvidenceStrength weak = FuzzingServer
                .classifyWindowTraceEvidenceStrength(
                        /* windowFired */ true,
                        /* supportGatePassed */ triDiff
                                .totalAllThreeCount() > 0,
                        // PRE_UPGRADE is not a mixed-version-relevant
                        // stage; even with support the window should
                        // only reach WEAK.
                        TraceWindow.StageKind.PRE_UPGRADE,
                        /* changedMessageCount */ 1,
                        /* upgradedBoundaryEventCount */ 1);
        assertEquals(TraceEvidenceStrength.WEAK, weak);
    }

    @Test
    void fractionsRemainBoundedEvenForAsymmetricLanes() {
        // Sanity check on the boundedness invariant. Previous normalization
        // divided missing by rolling-lane size, which blew past 1.0 whenever
        // rolling was much smaller than the baselines. The Phase 1 accessors
        // must return values in [0,1].
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("a", "b", "c", "d", "e", "f", "g", "h"),
                traceOf("a"),
                traceOf("a", "b", "c", "d", "e", "f", "g", "h"));

        assertTrue(triDiff.rollingExclusiveFraction() >= 0.0);
        assertTrue(triDiff.rollingExclusiveFraction() <= 1.0);
        assertTrue(triDiff.rollingMissingFraction() >= 0.0);
        assertTrue(triDiff.rollingMissingFraction() <= 1.0);
    }

    // ---------------------------------------------------------------
    // Phase 2 support / stage / change gates
    // ---------------------------------------------------------------

    /**
     * Phase 2 strict gates: strong-support baseline. Rolling introduces
     * 5 exclusive messages on top of a 5-message three-way-shared base.
     * Changed-message corroboration is present, baseline similarity is
     * perfect (the baselines were identical), and the stage is a post-
     * upgrade stage. All gates pass → STRONG.
     */
    @Test
    void phase2StrongWhenSupportStageAndChangeAllPass() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3",
                        "rolling_only_4", "rolling_only_5"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 1,
                /* upgradedBoundaryEventCount */ 1,
                /* rollingMinSimilarity */ 0.50,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertTrue(decision.exclusiveInteresting);
        assertTrue(decision.triDiffInteresting);
        assertTrue(decision.supportGatePassed,
                "all3=5 and baselineShared=5 clear strictGates()");
        assertTrue(decision.stageGatePassed);
        assertTrue(decision.changedMessageGatePassed);
        assertEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength);
    }

    /**
     * Support gate failure under strict gates: the 3-lane trace has no
     * three-way overlap ({@code all3=0}) even though the baselines agree
     * on 5 messages. The window still fires via rolling-exclusive churn
     * but must fall to UNSUPPORTED — this is the Apr15 all3=0 pattern
     * Phase 2 is designed to demote.
     */
    @Test
    void phase2UnsupportedWhenAllThreeIsZero() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("r1", "r2", "r3", "r4", "r5"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 3,
                /* upgradedBoundaryEventCount */ 1,
                /* rollingMinSimilarity */ 0.00,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertEquals(0, triDiff.totalAllThreeCount());
        assertTrue(decision.exclusiveInteresting);
        assertFalse(decision.supportGatePassed);
        assertEquals(TraceEvidenceStrength.UNSUPPORTED,
                decision.traceEvidenceStrength);
    }

    /**
     * PRE_UPGRADE windows cannot reach STRONG under the default policy.
     * Even with full three-way support, a matching baseline, and
     * corroborating change evidence, the stage gate blocks promotion and
     * the decision falls to WEAK.
     */
    @Test
    void phase2PreUpgradeDoesNotStrengthenByDefault() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.PRE_UPGRADE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 5,
                /* upgradedBoundaryEventCount */ 1,
                /* rollingMinSimilarity */ 0.40,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertTrue(decision.supportGatePassed);
        assertFalse(decision.stageGatePassed);
        assertEquals(TraceEvidenceStrength.WEAK,
                decision.traceEvidenceStrength);
    }

    /**
     * The override knob {@code preUpgradeCanStrengthenBranch=true}
     * reopens the stage gate for PRE_UPGRADE so offline replays can
     * measure the delta. The test above is the default; this one locks
     * in the override path.
     */
    @Test
    void phase2PreUpgradeCanStrengthenWhenConfigAllows() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TraceStrengthGates overrideGates = new TraceStrengthGates(
                /* minAllThreeCount */ 3,
                /* minBaselineSharedCount */ 3,
                /* minBaselineSimilarity */ 0.70,
                /* minChangedMessageCount */ 0,
                /* minUpgradedBoundaryCount */ 0,
                /* preUpgradeCanStrengthenBranch */ true,
                /* fallbackMinAllThreeCount */ 20,
                /* fallbackMaxRollingMinSimilarity */ 0.40);

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.PRE_UPGRADE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 5,
                /* upgradedBoundaryEventCount */ 1,
                /* rollingMinSimilarity */ 0.40,
                /* baselineSimilarity */ 1.00,
                overrideGates);

        assertTrue(decision.stageGatePassed,
                "override reopens PRE_UPGRADE");
        assertEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength);
    }

    /**
     * Baseline-agreement gate: when the two baselines disagree
     * materially ({@code baselineSimilarity < minBaselineSimilarity}),
     * the round is already unstable and promoting the rolling
     * divergence would amplify noise. The window drops to WEAK even
     * when the other gates all pass.
     */
    @Test
    void phase2WeakWhenBaselinesDisagree() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 5,
                /* upgradedBoundaryEventCount */ 1,
                /* rollingMinSimilarity */ 0.40,
                /* baselineSimilarity */ 0.55,
                strictGates());

        assertTrue(decision.supportGatePassed);
        assertTrue(decision.stageGatePassed);
        assertTrue(decision.changedMessageGatePassed);
        assertEquals(TraceEvidenceStrength.WEAK,
                decision.traceEvidenceStrength,
                "baselines disagreeing (0.55 < 0.70) demotes to WEAK");
    }

    /**
     * Change gate failure: all gates pass except that no changed-message
     * or upgraded-boundary evidence is present. The strong-support
     * fallback is also out of reach (neither all3 nor rollingMinSim
     * meets the fallback thresholds). Result: WEAK.
     */
    @Test
    void phase2WeakWhenNoCorroborationAndFallbackOutOfReach() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 0,
                /* upgradedBoundaryEventCount */ 0,
                /* rollingMinSimilarity */ 0.60,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertTrue(decision.supportGatePassed);
        assertTrue(decision.stageGatePassed);
        assertFalse(decision.changedMessageGatePassed,
                "no changed-message or boundary evidence");
        assertEquals(TraceEvidenceStrength.WEAK,
                decision.traceEvidenceStrength);
    }

    /**
     * Strong-support fallback: no changed-message / upgraded-boundary
     * evidence, but all3 is comfortably above the fallback floor AND
     * rolling min-similarity is low. This is the "very divergent from a
     * well-supported baseline" case and is allowed to reach STRONG.
     */
    @Test
    void phase2StrongViaFallbackOnHighSupportAndLowSim() {
        Trace baseline = traceOf(
                "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10",
                "m11", "m12", "m13", "m14", "m15", "m16", "m17", "m18", "m19",
                "m20", "m21", "m22", "m23", "m24", "m25");
        Trace rolling = traceOf(
                "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10",
                "m11", "m12", "m13", "m14", "m15", "m16", "m17", "m18", "m19",
                "m20", "m21", "m22", "m23", "m24", "m25",
                "rolling_only_1", "rolling_only_2", "rolling_only_3");
        Trace baseline2 = traceOf(
                "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10",
                "m11", "m12", "m13", "m14", "m15", "m16", "m17", "m18", "m19",
                "m20", "m21", "m22", "m23", "m24", "m25");
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                baseline, rolling, baseline2);

        assertTrue(triDiff.totalAllThreeCount() >= 20,
                "precondition: all3 clears the fallback floor");

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_FINAL_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 0,
                /* upgradedBoundaryEventCount */ 0,
                /* rollingMinSimilarity */ 0.30,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertTrue(decision.supportGatePassed);
        assertTrue(decision.stageGatePassed);
        assertFalse(decision.changedMessageGatePassed);
        assertEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength,
                "high-support + low-similarity fallback must reach STRONG");
    }

    /**
     * Windows that do not fire any trace rule must report NONE
     * regardless of how good the support/stage/change gates look.
     */
    @Test
    void phase2NotFiringIsAlwaysNone() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3"),
                traceOf("m1", "m2", "m3"),
                traceOf("m1", "m2", "m3"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 10,
                /* upgradedBoundaryEventCount */ 1,
                /* rollingMinSimilarity */ 0.10,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertFalse(decision.exclusiveInteresting);
        assertFalse(decision.missingInteresting);
        assertEquals(TraceEvidenceStrength.NONE,
                decision.traceEvidenceStrength);
    }

    /**
     * Corroboration is an OR of two alternative paths: changed-message
     * traffic and upgraded-boundary traffic. Each path has its own
     * minimum. When both minima are strict (3 and 3 below),
     * changed-message evidence alone — with zero boundary crossings —
     * must be enough to reach STRONG. Locks in the Phase 2 plan's
     * "at least one of" semantics and prevents a regression back to
     * AND combination.
     */
    @Test
    void phase2ChangedMessageAloneSatisfiesCorroboration() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TraceStrengthGates gates = new TraceStrengthGates(
                /* minAllThreeCount */ 3,
                /* minBaselineSharedCount */ 3,
                /* minBaselineSimilarity */ 0.70,
                /* minChangedMessageCount */ 3,
                /* minUpgradedBoundaryCount */ 3,
                /* preUpgradeCanStrengthenBranch */ false,
                // Disable the fallback so the change gate is the only
                // path to STRONG for this window.
                /* fallbackMinAllThreeCount */ 0,
                /* fallbackMaxRollingMinSimilarity */ 1.0);

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 5,
                /* upgradedBoundaryEventCount */ 0,
                /* rollingMinSimilarity */ 0.50,
                /* baselineSimilarity */ 1.00,
                gates);

        assertTrue(decision.supportGatePassed);
        assertTrue(decision.stageGatePassed);
        assertTrue(decision.changedMessageGatePassed,
                "changed-message >= minChangedMessageCount alone must satisfy corroboration");
        assertEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength);
    }

    /**
     * Mirror of the above: upgraded-boundary traffic alone (with zero
     * changed-message payloads) must satisfy corroboration when the
     * boundary threshold is met. The changed-message threshold is
     * strict and unreachable here so the test would fail under an AND
     * combination of the two paths.
     */
    @Test
    void phase2UpgradedBoundaryAloneSatisfiesCorroboration() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TraceStrengthGates gates = new TraceStrengthGates(
                /* minAllThreeCount */ 3,
                /* minBaselineSharedCount */ 3,
                /* minBaselineSimilarity */ 0.70,
                /* minChangedMessageCount */ 3,
                /* minUpgradedBoundaryCount */ 3,
                /* preUpgradeCanStrengthenBranch */ false,
                /* fallbackMinAllThreeCount */ 0,
                /* fallbackMaxRollingMinSimilarity */ 1.0);

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 0,
                /* upgradedBoundaryEventCount */ 5,
                /* rollingMinSimilarity */ 0.50,
                /* baselineSimilarity */ 1.00,
                gates);

        assertTrue(decision.supportGatePassed);
        assertTrue(decision.stageGatePassed);
        assertTrue(decision.changedMessageGatePassed,
                "upgraded-boundary >= minUpgradedBoundaryCount alone must satisfy corroboration");
        assertEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength);
    }

    /**
     * Neither corroboration path meets its minimum (changed=2 < 100,
     * boundary=2 < 100) so the change gate fails outright. The
     * strong-support fallback is still configured with high-support /
     * low-similarity thresholds the window satisfies. Result: STRONG
     * via fallback only — confirming the fallback is an independent
     * third path, not a tiebreaker that only runs when corroboration
     * is borderline.
     */
    @Test
    void phase2FallbackFiresIndependentlyOfCorroborationGate() {
        Trace baseline = traceOf(
                "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10",
                "m11", "m12", "m13", "m14", "m15", "m16", "m17", "m18", "m19",
                "m20", "m21", "m22", "m23", "m24", "m25");
        Trace rolling = traceOf(
                "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10",
                "m11", "m12", "m13", "m14", "m15", "m16", "m17", "m18", "m19",
                "m20", "m21", "m22", "m23", "m24", "m25",
                "rolling_only_1", "rolling_only_2", "rolling_only_3");
        Trace baseline2 = traceOf(
                "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10",
                "m11", "m12", "m13", "m14", "m15", "m16", "m17", "m18", "m19",
                "m20", "m21", "m22", "m23", "m24", "m25");
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                baseline, rolling, baseline2);
        assertTrue(triDiff.totalAllThreeCount() >= 20,
                "precondition: all3 clears the fallback floor");

        TraceStrengthGates gates = new TraceStrengthGates(
                /* minAllThreeCount */ 3,
                /* minBaselineSharedCount */ 3,
                /* minBaselineSimilarity */ 0.70,
                // Both corroboration minima are unreachable for this
                // window so the change gate cannot pass either path.
                /* minChangedMessageCount */ 100,
                /* minUpgradedBoundaryCount */ 100,
                /* preUpgradeCanStrengthenBranch */ false,
                /* fallbackMinAllThreeCount */ 20,
                /* fallbackMaxRollingMinSimilarity */ 0.40);

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_FINAL_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 2,
                /* upgradedBoundaryEventCount */ 2,
                /* rollingMinSimilarity */ 0.30,
                /* baselineSimilarity */ 1.00,
                gates);

        assertTrue(decision.supportGatePassed);
        assertTrue(decision.stageGatePassed);
        assertFalse(decision.changedMessageGatePassed,
                "neither corroboration path meets its minimum");
        assertEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength,
                "fallback is independent of the corroboration gate");
    }

    /**
     * Legacy six-argument overload must still work for the Phase 1
     * regression tests above. Its strength is permissively NONE (the
     * legacy caller does not care about strength labels).
     */
    @Test
    void phase2LegacyOverloadKeepsPhase1BehaviorAndReportsNone() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3"),
                traceOf("m1", "m2", "m3", "rolling_only_1", "rolling_only_2",
                        "rolling_only_3"),
                traceOf("m1", "m2", "m3"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION);

        assertTrue(decision.exclusiveInteresting);
        assertTrue(decision.triDiffInteresting);
        // The legacy overload uses permissive gates (all minima=0) and
        // does not feed a changed-message count, so the result is
        // STRONG only if stage/support/change all pass with minima=0.
        // all3=3, stage=POST_STAGE → supportGatePassed=true and
        // stageGatePassed=true; changedMessageCount=0 and upgraded=0
        // under permissive gates → no corroboration → WEAK. The
        // legacy overload's strength is not authoritative for the
        // Phase 2 path, but this test locks it in so future refactors
        // do not silently shift its semantics.
        assertEquals(TraceEvidenceStrength.WEAK,
                decision.traceEvidenceStrength);
    }

    private static TraceStrengthGates strictGates() {
        return new TraceStrengthGates(
                /* minAllThreeCount */ 3,
                /* minBaselineSharedCount */ 3,
                /* minBaselineSimilarity */ 0.70,
                /* minChangedMessageCount */ 0,
                /* minUpgradedBoundaryCount */ 0,
                /* preUpgradeCanStrengthenBranch */ false,
                /* fallbackMinAllThreeCount */ 20,
                /* fallbackMaxRollingMinSimilarity */ 0.40);
    }

    // ---------------------------------------------------------------
    // Phase 2 admission-path enforcement
    //
    // These tests chain window classification → round-level roll-up →
    // admission-reason selection the same way {@code updateStatus} does,
    // so they lock in the enforcement Phase 2 adds on top of Phase 1:
    //
    // 1. weak/unsupported trace never drives a trace-only admission, and
    // 2. weak/unsupported trace never upgrades BRANCH_ONLY to
    // BRANCH_AND_TRACE.
    //
    // The real {@code updateStatus} uses {@code isPhase2TraceAdmissible}
    // to compute {@code effectiveTraceInteresting} and then calls
    // {@code classifyAdmissionReason} with that value. The tests follow
    // the exact same sequence.
    // ---------------------------------------------------------------

    @Test
    void phase2AllThreeZeroDoesNotUpgradeBranchOnlyAdmission() {
        // Rolling is disjoint from both baselines → triDiff fires
        // exclusive but supportGatePassed=false → UNSUPPORTED.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("r1", "r2", "r3", "r4", "r5"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 0,
                /* upgradedBoundaryEventCount */ 0,
                /* rollingMinSimilarity */ 0.10,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertEquals(0, triDiff.totalAllThreeCount());
        assertTrue(decision.exclusiveInteresting,
                "the tri-diff rule still fires on rolling-only churn");
        assertEquals(TraceEvidenceStrength.UNSUPPORTED,
                decision.traceEvidenceStrength);

        // Round-level roll-up — one firing window, unsupported.
        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 0,
                        /* unsupportedFiringWindows */ 1,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.UNSUPPORTED, roundStrength);

        // Phase 2 gate: unsupported rounds are not admissible via the
        // trace path.
        boolean effectiveTraceInteresting = FuzzingServer
                .isPhase2TraceAdmissible(
                        /* traceInteresting */ true, roundStrength);
        assertFalse(effectiveTraceInteresting,
                "unsupported trace must not drive admission");

        // With branch coverage AND this unsupported trace, the
        // admission reason must stay BRANCH_ONLY — Phase 2's core
        // requirement.
        AdmissionReason reasonWithBranch = FuzzingServer
                .classifyAdmissionReason(
                        /* newBranchCoverage */ true,
                        effectiveTraceInteresting,
                        /* triDiffExclusiveFired */ true,
                        /* windowSimFired */ false,
                        /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_ONLY, reasonWithBranch,
                "unsupported trace must not upgrade BRANCH_ONLY to BRANCH_AND_TRACE");

        // Without branch coverage, the raw admit flag is false: no
        // branch novelty and no Phase 2-admissible trace → no
        // admission at all.
        boolean addToCorpusNoBranch = false || effectiveTraceInteresting;
        assertFalse(addToCorpusNoBranch,
                "unsupported trace alone must not drive a trace-only admission");
    }

    @Test
    void phase2PreUpgradeDoesNotUpgradeBranchOnlyAdmission() {
        // Full three-way support and rolling-exclusive churn, but the
        // stage is PRE_UPGRADE. The per-window classifier labels this
        // WEAK (not UNSUPPORTED) because support passes but the stage
        // gate blocks strength.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.PRE_UPGRADE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 5,
                /* upgradedBoundaryEventCount */ 2,
                /* rollingMinSimilarity */ 0.30,
                /* baselineSimilarity */ 1.00,
                strictGates());

        assertTrue(decision.exclusiveInteresting);
        assertTrue(decision.supportGatePassed);
        assertFalse(decision.stageGatePassed,
                "PRE_UPGRADE blocks stage gate by default");
        assertEquals(TraceEvidenceStrength.WEAK,
                decision.traceEvidenceStrength);

        // Round-level roll-up — one weak window.
        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 1,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.WEAK, roundStrength);

        boolean effectiveTraceInteresting = FuzzingServer
                .isPhase2TraceAdmissible(
                        /* traceInteresting */ true, roundStrength);
        assertFalse(effectiveTraceInteresting,
                "PRE_UPGRADE weak trace must not drive admission");

        // With branch coverage: BRANCH_ONLY, not BRANCH_AND_TRACE.
        AdmissionReason reasonWithBranch = FuzzingServer
                .classifyAdmissionReason(
                        /* newBranchCoverage */ true,
                        effectiveTraceInteresting,
                        /* triDiffExclusiveFired */ true,
                        /* windowSimFired */ false,
                        /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_ONLY, reasonWithBranch);
    }

    @Test
    void phase2AggregateSimOnlyIsNotAdmissible() {
        // Aggregate similarity fires with no per-window trace signal.
        // The round-level classifier labels this WEAK in Phase 0 so
        // Phase 2 enforcement must drop it from admission. This locks
        // in the Apr12 concern that aggregate-sim-only is noise.
        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 0,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ true);
        assertEquals(TraceEvidenceStrength.WEAK, roundStrength);

        boolean effective = FuzzingServer.isPhase2TraceAdmissible(
                /* traceInteresting */ true, roundStrength);
        assertFalse(effective);

        AdmissionReason reasonWithBranch = FuzzingServer
                .classifyAdmissionReason(
                        /* newBranchCoverage */ true,
                        effective,
                        /* triDiffExclusiveFired */ false,
                        /* windowSimFired */ false,
                        /* aggregateSimFired */ true);
        assertEquals(AdmissionReason.BRANCH_ONLY, reasonWithBranch);
    }

    @Test
    void phase2StrongTraceStillUpgradesBranchOnlyAdmission() {
        // Positive control: when the round IS strong, the enforcement
        // does nothing and BRANCH_AND_TRACE still fires. This is the
        // Phase 1 happy path preserved under Phase 2.
        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 2,
                        /* weakFiringWindows */ 1,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.STRONG, roundStrength);

        boolean effective = FuzzingServer.isPhase2TraceAdmissible(
                /* traceInteresting */ true, roundStrength);
        assertTrue(effective);

        AdmissionReason reasonWithBranch = FuzzingServer
                .classifyAdmissionReason(
                        /* newBranchCoverage */ true,
                        effective,
                        /* triDiffExclusiveFired */ true,
                        /* windowSimFired */ false,
                        /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_AND_TRACE, reasonWithBranch);

        AdmissionReason reasonTraceOnly = FuzzingServer
                .classifyAdmissionReason(
                        /* newBranchCoverage */ false,
                        effective,
                        /* triDiffExclusiveFired */ true,
                        /* windowSimFired */ false,
                        /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.TRACE_ONLY_TRIDIFF_EXCLUSIVE,
                reasonTraceOnly);
    }

    // ---------------------------------------------------------------
    // Phase 2 upgraded-boundary crossing accounting
    // ---------------------------------------------------------------

    @Test
    void boundaryCrossingIgnoresNonUpgradedWithinVersionTraffic() {
        // Three nodes, one (N2) upgraded. All traffic is N0 <-> N1 —
        // entirely within the non-upgraded half of the cluster. The
        // old counter (rawUpgradedNodeSet.size() == 1) would have
        // reported a non-zero boundary count; the per-event counter
        // must return zero.
        Trace merged = new Trace();
        addEntry(merged, "executor1-N0", "executor1-N1", "msgA");
        addEntry(merged, "executor1-N1", "executor1-N0", "msgB");
        addEntry(merged, "executor1-N0", "executor1-N1", "msgC");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(2));
        int count = FuzzingServer.countUpgradedBoundaryCrossings(merged,
                upgraded);
        assertEquals(0, count,
                "upgraded node not participating in any traffic must not count");
    }

    @Test
    void boundaryCrossingCountsOnlyCrossVersionEdges() {
        // N0 and N1 upgraded, N2 still on old bits. Mixed pattern:
        // N0 -> N1 (both upgraded; within-version, no crossing)
        // N1 -> N2 (upgraded -> old; crossing)
        // N2 -> N0 (old -> upgraded; crossing)
        // N2 -> N2 (self; not a crossing because both endpoints
        // share the same set membership)
        // N0 -> N2 (upgraded -> old; crossing)
        Trace merged = new Trace();
        addEntry(merged, "executor1-N0", "executor1-N1", "msg0");
        addEntry(merged, "executor1-N1", "executor1-N2", "msg1");
        addEntry(merged, "executor1-N2", "executor1-N0", "msg2");
        addEntry(merged, "executor1-N2", "executor1-N2", "msg3");
        addEntry(merged, "executor1-N0", "executor1-N2", "msg4");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(0, 1));
        int count = FuzzingServer.countUpgradedBoundaryCrossings(merged,
                upgraded);
        assertEquals(3, count);
    }

    @Test
    void boundaryCrossingReturnsZeroWhenUpgradedSetIsEmpty() {
        Trace merged = new Trace();
        addEntry(merged, "executor1-N0", "executor1-N1", "msgA");

        assertEquals(0, FuzzingServer.countUpgradedBoundaryCrossings(
                merged, Collections.<Integer> emptySet()));
        assertEquals(0, FuzzingServer.countUpgradedBoundaryCrossings(
                merged, null));
        assertEquals(0, FuzzingServer.countUpgradedBoundaryCrossings(
                null, new HashSet<>(Arrays.asList(0))));
    }

    @Test
    void boundaryCrossingSkipsEntriesWithUnparseableEndpoints() {
        // Malformed / missing endpoint IDs must never be counted as
        // crossings — otherwise a broken tracker could inflate
        // corroboration silently.
        Trace merged = new Trace();
        addEntry(merged, null, "executor1-N1", "null-src");
        addEntry(merged, "executor1-N0", null, "null-dst");
        addEntry(merged, "garbage", "also-garbage", "no-index");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(0, 1));
        int count = FuzzingServer.countUpgradedBoundaryCrossings(merged,
                upgraded);
        assertEquals(0, count);
    }

    @Test
    void extractNodeIndexHandlesAllFormats() {
        assertEquals(0, FuzzingServer.extractNodeIndex("executor1-N0"));
        assertEquals(7, FuzzingServer.extractNodeIndex("executor1-N7"));
        assertEquals(12, FuzzingServer.extractNodeIndex("SrnNTLLS-N12"));
        assertEquals(3, FuzzingServer.extractNodeIndex("N3"));
        assertEquals(5, FuzzingServer.extractNodeIndex("5"));
        assertEquals(-1, FuzzingServer.extractNodeIndex(null));
        assertEquals(-1, FuzzingServer.extractNodeIndex(""));
        assertEquals(-1, FuzzingServer.extractNodeIndex("null"));
        assertEquals(-1, FuzzingServer.extractNodeIndex("N"));
        assertEquals(-1, FuzzingServer.extractNodeIndex("Nabc"));
        assertEquals(-1, FuzzingServer.extractNodeIndex("-N"));
    }

    /**
     * Phase 2 admission-path integration check: a window with several
     * upgraded nodes but zero cross-version traffic must not reach
     * STRONG. Under the old {@code rawUpgradedNodeSet.size()}
     * corroboration counter this would look like upgraded-boundary
     * evidence > 0 and (with strict change-gate knobs) could promote to
     * STRONG. Phase 2 ties the corroboration counter to actual
     * per-event crossings, so the same window falls to WEAK instead.
     */
    @Test
    void phase2UpgradedNodesWithoutCrossVersionTrafficStayWeak() {
        // Window-level signals: full three-way support + rolling
        // exclusive churn.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = compute(
                traceOf("m1", "m2", "m3", "m4", "m5"),
                traceOf("m1", "m2", "m3", "m4", "m5",
                        "rolling_only_1", "rolling_only_2", "rolling_only_3"),
                traceOf("m1", "m2", "m3", "m4", "m5"));

        // Per-event boundary crossings derived from the merged rolling
        // trace: three upgraded nodes, but every send stays within the
        // non-upgraded half.
        Trace merged = new Trace();
        addEntry(merged, "executor1-N0", "executor1-N1", "m1");
        addEntry(merged, "executor1-N1", "executor1-N0", "m2");
        Set<Integer> upgraded = new HashSet<>(Arrays.asList(2, 3, 4));
        int crossings = FuzzingServer.countUpgradedBoundaryCrossings(
                merged, upgraded);
        assertEquals(0, crossings);

        // Strict gates that require corroboration (either changed
        // message count or upgraded-boundary count). With zero
        // crossings and zero changed-message traffic the change gate
        // must fail and the window must drop to WEAK.
        TraceStrengthGates gatesRequiringCorroboration = new TraceStrengthGates(
                /* minAllThreeCount */ 3,
                /* minBaselineSharedCount */ 3,
                /* minBaselineSimilarity */ 0.70,
                /* minChangedMessageCount */ 0,
                /* minUpgradedBoundaryCount */ 0,
                /* preUpgradeCanStrengthenBranch */ false,
                // Disable the high-support fallback so the change gate
                // is the only path to STRONG for this window.
                /* fallbackMinAllThreeCount */ 0,
                /* fallbackMaxRollingMinSimilarity */ 1.0);

        TriDiffWindowDecision decision = FuzzingServer.evaluateTriDiffWindow(
                triDiff,
                TraceWindow.StageKind.POST_STAGE,
                EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION,
                MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION,
                /* windowSimInteresting */ false,
                /* changedMessageCount */ 0,
                crossings,
                /* rollingMinSimilarity */ 0.50,
                /* baselineSimilarity */ 1.00,
                gatesRequiringCorroboration);

        assertTrue(decision.supportGatePassed);
        assertTrue(decision.stageGatePassed);
        assertFalse(decision.changedMessageGatePassed,
                "no cross-version traffic and no changed-message payloads");
        assertEquals(TraceEvidenceStrength.WEAK,
                decision.traceEvidenceStrength);
    }

    private static void addEntry(Trace trace, String nodeId, String peerId,
            String message) {
        trace.recordSend(
                "BoundaryTest.fakeSend",
                10000001,
                new int[] { 0 },
                message,
                org.zlab.net.tracker.SendMeta.builder()
                        .nodeId(nodeId)
                        .peerId(peerId)
                        .messageType("UnitMessage")
                        .build(),
                message);
    }

    // ---------------------------------------------------------------
    // Phase 0 topology-aware boundary resolution
    // ---------------------------------------------------------------

    private static TopologySnapshot buildTopology(String... nodeSpecs) {
        TopologyNormalizer normalizer = new TopologyNormalizer();
        for (int i = 0; i < nodeSpecs.length; i++) {
            String[] parts = nodeSpecs[i].split("\\|", -1);
            String role = parts[0];
            String[] aliases = new String[parts.length - 1];
            for (int j = 1; j < parts.length; j++) {
                aliases[j - 1] = parts[j];
            }
            normalizer.registerNode(i, role, aliases);
        }
        return normalizer.snapshot();
    }

    @Test
    void boundaryCrossingResolvedViaHostnameTopologyMapping() {
        // Scenario: peerId recorded as a hostname (no N-suffix) but the
        // executor registered the hostname -> index mapping. The new
        // topology-aware counter must recognize the crossing that the
        // legacy numeric-only parser silently skipped.
        TopologySnapshot topology = buildTopology(
                "datanode|DC3N0|dn0.internal|10.0.0.1",
                "datanode|DC3N1|dn1.internal|10.0.0.2",
                "namenode|DC3N2|nn.internal|10.0.0.3");
        Trace merged = new Trace();
        addEntry(merged, "SrnNTLLS-N0", "nn.internal", "mutation1");
        addEntry(merged, "nn.internal", "SrnNTLLS-N0", "ack1");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(2));
        BoundaryResolutionResult detailed = FuzzingServer
                .countUpgradedBoundaryCrossingsDetailed(merged, upgraded,
                        topology);
        assertEquals(2, detailed.totalEventCount);
        assertEquals(2, detailed.crossingCount,
                "both hostname-peer events must resolve as crossings");
        assertEquals(4, detailed.indexResolvedEndpointCount,
                "all endpoints resolved to a specific node");
        assertEquals(0, detailed.unresolvedEndpointCount);
    }

    @Test
    void boundaryCrossingResolvedViaIpTopologyMapping() {
        // Scenario: Cassandra 4.x routinely logs peer as "/10.0.0.3" with
        // a leading slash from InetAddress.toString. The snapshot must
        // resolve the leading-slash form back to the correct index.
        TopologySnapshot topology = buildTopology(
                "cassandra|DC3N0|10.0.0.1",
                "cassandra|DC3N1|10.0.0.2",
                "cassandra|DC3N2|10.0.0.3");
        Trace merged = new Trace();
        addEntry(merged, "executor-N0", "/10.0.0.2:9042", "mutation1");
        addEntry(merged, "executor-N1", "/10.0.0.0", "unknown-ip");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(1));
        BoundaryResolutionResult detailed = FuzzingServer
                .countUpgradedBoundaryCrossingsDetailed(merged, upgraded,
                        topology);
        assertEquals(2, detailed.totalEventCount);
        assertEquals(1, detailed.crossingCount,
                "N0->N1 crosses upgraded boundary; N1->unknown does not");
        // First entry: both endpoints resolved (N0 via N-suffix, peer via
        // IP with port). Second entry: N1 via N-suffix, peer IP unknown.
        assertEquals(3, detailed.indexResolvedEndpointCount);
        assertEquals(1, detailed.unresolvedEndpointCount,
                "IP outside the registered cluster is unresolved");
    }

    @Test
    void boundaryCrossingCountsUniqueRoleResolution() {
        // HDFS-style topology: one namenode, two datanodes. A send from
        // a datanode (index resolved) to "namenode" role is ambiguous by
        // id lookup alone but uniquely resolvable by role — this is the
        // Phase 0 "role-unique" path that must count as corroborating.
        TopologySnapshot topology = buildTopology(
                "datanode|DC3N0",
                "datanode|DC3N1",
                "namenode|DC3N2");
        Trace merged = new Trace();
        // peerId is the role name itself (no id mapping exists for
        // "namenode" as a raw id, but the role resolves uniquely).
        addEntry(merged, "executor-N0", "namenode", "heartbeat");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(2));
        BoundaryResolutionResult detailed = FuzzingServer
                .countUpgradedBoundaryCrossingsDetailed(merged, upgraded,
                        topology);
        assertEquals(1, detailed.crossingCount,
                "role-unique resolution must count the crossing");
        assertEquals(1, detailed.indexResolvedEndpointCount,
                "N0 endpoint resolves via numeric parsing");
        assertEquals(1, detailed.roleResolvedEndpointCount,
                "peer endpoint resolves via unique role");
    }

    @Test
    void boundaryCrossingAmbiguousRoleIsDiagnosticOnly() {
        // Cassandra-style topology: every node has the same role. A raw
        // peerId that resolves only via role must not count as a
        // boundary crossing — role membership alone is not enough to
        // pin a node.
        TopologySnapshot topology = buildTopology(
                "cassandra|DC3N0",
                "cassandra|DC3N1",
                "cassandra|DC3N2");
        Trace merged = new Trace();
        addEntry(merged, "executor-N0", "cassandra", "gossip");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(1));
        BoundaryResolutionResult detailed = FuzzingServer
                .countUpgradedBoundaryCrossingsDetailed(merged, upgraded,
                        topology);
        assertEquals(0, detailed.crossingCount,
                "ambiguous role must not be treated as corroborating");
        assertEquals(1, detailed.indexResolvedEndpointCount,
                "sender side resolves by numeric parsing");
        assertEquals(1, detailed.roleAmbiguousEndpointCount,
                "peer side is diagnostic-only under ambiguous role");
    }

    @Test
    void metadataCoverageDistinguishesMissingIds() {
        // The boundary resolver's unresolved counter is the metadata
        // coverage path used by trace_metadata_coverage.csv — missing
        // peer ids must increment unresolvedEndpoints even with a
        // snapshot present.
        TopologySnapshot topology = buildTopology(
                "datanode|DC3N0",
                "namenode|DC3N1");
        Trace merged = new Trace();
        addEntry(merged, "executor-N0", null, "m-null-peer");
        addEntry(merged, "garbage", "also-garbage", "m-all-garbage");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(1));
        BoundaryResolutionResult detailed = FuzzingServer
                .countUpgradedBoundaryCrossingsDetailed(merged, upgraded,
                        topology);
        assertEquals(0, detailed.crossingCount);
        assertEquals(1, detailed.indexResolvedEndpointCount,
                "only executor-N0 resolves");
        assertEquals(3, detailed.unresolvedEndpointCount,
                "null peerId + two garbage endpoints stay unresolved");
    }

    @Test
    void metadataCoverageRowSeparatesMissingAmbiguousAndUnresolvedPeers() {
        // Exercise the actual coverage-row builder: peer-id
        // classification must stay distinct between missing (null /
        // empty), role-ambiguous (peer-to-peer), and unresolved
        // (unregistered raw id) so Phase 1 sees each gap separately.
        TopologySnapshot topology = buildTopology(
                "cassandra|DC3N0|10.0.0.1",
                "cassandra|DC3N1|10.0.0.2",
                "namenode|nn.internal");

        Trace merged = new Trace();
        // 1. Resolved (index) — counts toward ID coverage, not gaps.
        merged.recordSend("metaCov.fakeSend", 1, new int[] { 0 },
                "m1",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .peerId("10.0.0.2")
                        .nodeRole("cassandra")
                        .peerRole("cassandra")
                        .messageType("UnitMessage")
                        .logicalMessageId("lmid-1")
                        .deliveryId("did-1")
                        .build(),
                "m1");
        // 2. Role-ambiguous peer (shared Cassandra role).
        merged.recordSend("metaCov.fakeSend", 2, new int[] { 0 },
                "m2",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .peerId("cassandra")
                        .nodeRole("cassandra")
                        .peerRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m2");
        // 3. Unresolved peer (hostname not registered anywhere).
        merged.recordSend("metaCov.fakeSend", 3, new int[] { 0 },
                "m3",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .peerId("mystery-host")
                        .nodeRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m3");
        // 4. Missing peer (null).
        merged.recordSend("metaCov.fakeSend", 4, new int[] { 0 },
                "m4",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .nodeRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m4");
        // 5. Empty-string peer (runtime treats as missing).
        merged.recordSend("metaCov.fakeSend", 5, new int[] { 0 },
                "m5",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .peerId("")
                        .nodeRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m5");
        // 6. Sentinel "null" peer (must also count as missing).
        merged.recordSend("metaCov.fakeSend", 6, new int[] { 0 },
                "m6",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .peerId("null")
                        .nodeRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m6");

        TraceMetadataCoverageRow row = FuzzingServer
                .buildTraceMetadataCoverageRow(
                        /* round */ 42L,
                        /* testPacketId */ 7,
                        /* laneName */ "Rolling",
                        merged,
                        topology);

        assertEquals(42L, row.round);
        assertEquals(7, row.testPacketId);
        assertEquals("Rolling", row.laneName);
        assertEquals(6, row.totalEntries);
        assertEquals(1, row.entriesWithLogicalMessageId);
        assertEquals(1, row.entriesWithDeliveryId);
        assertEquals(6, row.entriesWithNodeRole);
        assertEquals(2, row.entriesWithPeerRole,
                "only entries 1 and 2 carry a peerRole");
        assertEquals(3, row.entriesWithMissingPeerId,
                "null + empty + \"null\" sentinel must all count as missing");
        assertEquals(1, row.entriesWithRoleAmbiguousPeerId,
                "peer-to-peer role must stay in the ambiguous bucket");
        assertEquals(1, row.entriesWithUnresolvedPeerId,
                "mystery-host is non-empty but not registered anywhere");
        assertEquals(3, row.topologyNodeCount);
        assertTrue(row.topologyIdMappingCount >= 3,
                "snapshot tracks every registered alias");

        // Sanity: the CSV schema must carry the new counters so offline
        // tooling can count them separately.
        String header = TraceMetadataCoverageRow.csvHeader();
        assertTrue(header.contains("entries_with_missing_peer_id"));
        assertTrue(header.contains("entries_with_role_ambiguous_peer_id"));
        assertTrue(header.contains("entries_with_unresolved_peer_id"));
    }

    @Test
    void metadataCoverageRowHandlesNullTraceAndNullSnapshot() {
        // Degraded lanes must still produce a row with all counters
        // zero and the snapshot-derived metadata zeroed so downstream
        // CSV consumers do not need to special-case missing rows.
        TraceMetadataCoverageRow row = FuzzingServer
                .buildTraceMetadataCoverageRow(
                        /* round */ 1L,
                        /* testPacketId */ 0,
                        /* laneName */ "OnlyOld",
                        /* mergedTrace */ null,
                        /* snapshot */ null);
        assertEquals(0, row.totalEntries);
        assertEquals(0, row.entriesWithMissingPeerId);
        assertEquals(0, row.entriesWithRoleAmbiguousPeerId);
        assertEquals(0, row.entriesWithUnresolvedPeerId);
        assertEquals(0, row.topologyNodeCount);
        assertEquals(0, row.topologyIdMappingCount);
    }

    @Test
    void boundaryCrossingIgnoresOutOfRangeNumericIndexWithSnapshot() {
        // Regression: a peer id like "N7" must not be treated as
        // INDEX_RESOLVED when the active cluster only has three nodes.
        // The snapshot's resolveEndpoint bounds check (indexToRole
        // containsKey) is the source of truth; the server-side resolver
        // must not re-run an unbounded numeric parser ahead of it.
        TopologySnapshot topology = buildTopology(
                "datanode|DC3N0|10.0.0.1",
                "datanode|DC3N1|10.0.0.2",
                "namenode|DC3N2|10.0.0.3");
        Trace merged = new Trace();
        // Known sender (N0) paired with an out-of-range N-form peer.
        addEntry(merged, "executor-N0", "stale-N7",
                "replay-from-larger-cluster");

        Set<Integer> upgraded = new HashSet<>(Arrays.asList(2));
        BoundaryResolutionResult detailed = FuzzingServer
                .countUpgradedBoundaryCrossingsDetailed(merged, upgraded,
                        topology);
        assertEquals(1, detailed.totalEventCount);
        assertEquals(0, detailed.crossingCount,
                "out-of-range peer must not be counted as a crossing");
        assertEquals(1, detailed.indexResolvedEndpointCount,
                "sender N0 still resolves via the snapshot");
        assertEquals(1, detailed.unresolvedEndpointCount,
                "out-of-range N-form must stay unresolved");
        assertEquals(0, detailed.roleResolvedEndpointCount);
        assertEquals(0, detailed.roleAmbiguousEndpointCount);
    }

    @Test
    void topologySnapshotRecognizesNTraceNodeIdAlias() {
        // NET_TRACE_NODE_ID emits `<executorID>-N<idx>`. The Executor
        // registers that form on the normalizer so the snapshot can
        // resolve it by id lookup in addition to numeric parsing.
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(0, "namenode", "ABCD1234-N0", "DC3N0");
        TopologySnapshot snapshot = normalizer.snapshot();

        ResolvedTraceEndpoint viaIdLookup = snapshot
                .resolveEndpoint("ABCD1234-N0");
        assertEquals(ResolvedTraceEndpoint.Resolution.INDEX_RESOLVED,
                viaIdLookup.resolution);
        assertEquals(0, viaIdLookup.nodeIndex);
        assertEquals("namenode", viaIdLookup.role);

        ResolvedTraceEndpoint viaHostname = snapshot
                .resolveEndpoint("DC3N0");
        assertEquals(ResolvedTraceEndpoint.Resolution.INDEX_RESOLVED,
                viaHostname.resolution);
        assertEquals(0, viaHostname.nodeIndex);
    }

    // ---------------------------------------------------------------
    // Phase 0 mode-5 cleanup: changedMessage retirement
    // ---------------------------------------------------------------

    @Test
    void modeFiveDoesNotUseChangedMessageRollingTraceCorroboration() {
        Config.Configuration conf = new Config.Configuration();
        conf.testingMode = 5;
        assertFalse(conf.useChangedMessageRollingTraceCorroboration(),
                "mode 5 must retire changedMessage from the rolling trace path");
        assertFalse(conf.isBranchOnlyBaselineMode(),
                "mode 5 is not the branch-only baseline");
    }

    @Test
    void modeSixIsExplicitBranchOnlyBaseline() {
        Config.Configuration conf = new Config.Configuration();
        conf.testingMode = 6;
        conf.normalizeModeFlags();
        assertFalse(conf.useChangedMessageRollingTraceCorroboration(),
                "mode 6 disables rolling trace altogether");
        assertTrue(conf.isBranchOnlyBaselineMode(),
                "mode 6 is the explicit branch-only / trace-off baseline");
        // Mode-6 normalization must continue to force trace features off
        // so the branch-only baseline stays clean.
        assertFalse(conf.useTrace);
        assertFalse(conf.useCanonicalTraceSimilarity);
        assertFalse(conf.useCanonicalMessageIdentityDiff);
    }

    @Test
    void nonRollingModesKeepChangedMessageCorroboration() {
        // Modes 0-4 continue to treat changedMessage as an active
        // corroborator — Phase 0 intentionally does not widen the
        // cleanup.
        for (int mode : new int[] { 0, 2, 3, 4 }) {
            Config.Configuration conf = new Config.Configuration();
            conf.testingMode = mode;
            assertTrue(conf.useChangedMessageRollingTraceCorroboration(),
                    "mode " + mode + " must keep changedMessage behavior");
            assertFalse(conf.isBranchOnlyBaselineMode(),
                    "mode " + mode + " is not the branch-only baseline");
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static DiffComputeMessageTriDiff.MessageTriDiffResult compute(
            Trace oo,
            Trace ro, Trace nn) {
        return DiffComputeMessageTriDiff.compute(oo, ro, nn);
    }

    private static Trace traceOf(String... messages) {
        Trace trace = new Trace();
        int idx = 0;
        for (String message : messages) {
            trace.recordSend("TriDiffDecisionTest.fakeSend", 10000001,
                    new int[] { idx }, message,
                    SendMeta.builder().messageType("UnitMessage").build(),
                    message);
            idx++;
        }
        return trace;
    }
}
