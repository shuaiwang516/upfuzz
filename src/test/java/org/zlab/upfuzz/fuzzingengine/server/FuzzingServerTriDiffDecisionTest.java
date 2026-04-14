package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.zlab.net.tracker.SendMeta;
import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.diff.DiffComputeMessageTriDiff;
import org.zlab.upfuzz.fuzzingengine.server.FuzzingServer.TriDiffWindowDecision;
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
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
