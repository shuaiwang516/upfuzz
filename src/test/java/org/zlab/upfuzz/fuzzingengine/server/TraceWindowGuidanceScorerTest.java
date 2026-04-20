package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.zlab.net.tracker.SendMeta;
import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.diff.DiffComputeMessageTriDiff;
import org.zlab.net.tracker.flow.BoundaryOracle;
import org.zlab.net.tracker.flow.FlowExtractionResult;
import org.zlab.net.tracker.flow.TraceFlowExtractor;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;

/**
 * Phase 3 regression tests for {@link TraceWindowGuidanceScorer}.
 *
 * <p>The scorer is exercised through a set of small, hand-built flow
 * extractions that cover the Phase 3 plan's required test cases:
 * <ul>
 * <li>family-only support stays weak,</li>
 * <li>baseline-baseline disagreement keeps a round from being falsely
 * promoted,</li>
 * <li>good baseline agreement plus rolling-only divergence reaches
 * STRONG,</li>
 * <li>background-only overlap cannot become STRONG,</li>
 * <li>repeated rolling-only upgrade-critical families are labelled
 * {@link TraceEvidenceStrength#UNSUPPORTED_BUT_REPEATABLE},</li>
 * <li>Cassandra / HDFS / HBase ordering anomalies surface without
 * rewarding background chatter,</li>
 * <li>boundary corroboration rescues a supported mixed-version window,</li>
 * <li>the tri-execution contract is preserved (baseline-agreement and
 * rolling-divergence components are tracked separately).</li>
 * </ul>
 */
class TraceWindowGuidanceScorerTest {

    private static final int EXCLUSIVE_MIN_COUNT = 3;
    private static final double EXCLUSIVE_MIN_FRACTION = 0.05;
    private static final int MISSING_MIN_COUNT = 3;
    private static final double MISSING_MIN_FRACTION = 0.05;

    private static TraceWindowGuidanceScorer.Weights defaultWeights() {
        return new TraceWindowGuidanceScorer.Weights(
                /* upgradeCriticalWeight */ 1.0,
                /* backgroundWeight */ 0.2,
                /* unknownWeight */ 0.4,
                /* strongScoreThreshold */ 0.35,
                /* weakScoreThreshold */ 0.10,
                /* boundaryBonus */ 0.15,
                /* orderBonusCap */ 0.10,
                /* backgroundCap */ 0.10,
                /* minBaselineAgreementForStrong */ 0.55,
                /* minRollingDivergenceForStrong */ 0.20);
    }

    // ---------------------------------------------------------------
    // Phase 1 admission contract preserved
    // ---------------------------------------------------------------

    @Test
    void rollingExclusiveChurnStillAdmits() {
        // Plain Phase 1 admission: rolling lane adds 5 messages neither
        // baseline has. The scorer must preserve the
        // exclusiveInteresting / triDiffInteresting booleans.
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("UNK", 5, 0),
                traceOf("UNK", 10, 0),
                traceOf("UNK", 5, 0));
        FlowExtractionResult empty = FlowExtractionResult.empty();

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, empty, empty, empty,
                TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ false,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertTrue(decision.exclusiveInteresting);
        assertTrue(decision.triDiffInteresting);
        assertTrue(decision.windowFired);
    }

    @Test
    void missingOnlyDoesNotAdmitButStaysObservable() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("UNK", 10, 0),
                traceOf("UNK", 2, 0),
                traceOf("UNK", 10, 0));
        FlowExtractionResult empty = FlowExtractionResult.empty();

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, empty, empty, empty,
                TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ false,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertFalse(decision.exclusiveInteresting);
        assertTrue(decision.missingInteresting,
                "missing counter stays visible for observability");
        assertFalse(decision.triDiffInteresting);
        assertFalse(decision.windowFired);
        assertEquals(TraceEvidenceStrength.NONE,
                decision.traceEvidenceStrength);
    }

    @Test
    void preUpgradeMissingChurnIsSuppressed() {
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("UNK", 10, 0),
                traceOf("UNK", 2, 0),
                traceOf("UNK", 10, 0));
        FlowExtractionResult empty = FlowExtractionResult.empty();

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, empty, empty, empty,
                TraceWindow.StageKind.PRE_UPGRADE,
                /* windowSimInteresting */ false,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertFalse(decision.exclusiveInteresting);
        assertFalse(decision.missingInteresting,
                "PRE_UPGRADE suppresses the missing observability counter");
    }

    // ---------------------------------------------------------------
    // Support classification
    // ---------------------------------------------------------------

    @Test
    void unsupportedWithoutUpgradeCriticalStaysUnsupported() {
        // Rolling lane carries only UNK traffic that neither baseline
        // has. No 3-way overlap anywhere. No upgrade-critical exclusive
        // events → plain UNSUPPORTED, not UNSUPPORTED_BUT_REPEATABLE.
        FlowExtractionResult oo = extract("s", traceOf("UNK", 4, 0));
        FlowExtractionResult ro = extract("s", traceOf("UNK", 4, 10));
        FlowExtractionResult nn = extract("s", traceOf("UNK", 4, 0));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 4, 0), traceOf("B", 4, 0), traceOf("A", 4, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertEquals(TraceSupportClass.UNSUPPORTED, decision.supportClass);
        assertEquals(TraceEvidenceStrength.UNSUPPORTED,
                decision.traceEvidenceStrength);
        assertFalse(decision.supportGatePassed);
    }

    @Test
    void rollingOnlyUpgradeCriticalFamilyIsUnsupportedButRepeatable() {
        // HDFS 2.10.2 → 3.3.6-style pattern from Apr16: rolling lane
        // produces CLIENT_NAMESPACE_MUTATION traffic that neither
        // baseline captured. The tri-diff fires via rolling-exclusive
        // messages, but there is no 3-way overlap on any family. The
        // scorer must surface UNSUPPORTED_BUT_REPEATABLE so scheduler
        // and artifact code can still track it.
        FlowExtractionResult oo = extract("s",
                cassandraVerbTrace("GOSSIP_SYN", 4, 0));
        FlowExtractionResult ro = extract("s",
                hdfsRpcTrace("ClientNamenodeProtocol", "create", 5, 100));
        FlowExtractionResult nn = extract("s",
                cassandraVerbTrace("GOSSIP_SYN", 4, 0));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 5, 0), traceOf("B", 5, 0), traceOf("A", 5, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertEquals(TraceSupportClass.UNSUPPORTED, decision.supportClass);
        assertTrue(decision.rollingOnlyUpgradeCriticalPresent);
        assertTrue(decision.rollingExclusiveUpgradeCriticalEventCount > 0);
        assertEquals(TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                decision.traceEvidenceStrength);
        assertNotEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength,
                "unsupported-but-repeatable must never auto-promote to STRONG");
    }

    @Test
    void backgroundOnlySupportCannotReachStrong() {
        // Three lanes carry identical background gossip traffic. Even
        // if the rolling lane adds a splash of rolling-only exclusive
        // messages and boundary evidence exists, the support is
        // BACKGROUND_ONLY and the scorer must cap the composite at
        // traceBackgroundCap.
        FlowExtractionResult oo = extract("s",
                cassandraVerbTrace("GOSSIP_SYN", 20, 0));
        FlowExtractionResult ro = extract("s",
                cassandraVerbTrace("GOSSIP_SYN", 20, 10));
        FlowExtractionResult nn = extract("s",
                cassandraVerbTrace("GOSSIP_SYN", 20, 0));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 20, 0), traceOf("A", 20, 5), traceOf("A", 20, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 10,
                /* boundaryInvolvedRollingFlowCount */ 5,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertEquals(TraceSupportClass.BACKGROUND_ONLY, decision.supportClass);
        assertNotEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength,
                "background-only overlap must never reach STRONG");
        assertTrue(decision.backgroundOnly);
        assertTrue(decision.compositeScore <= defaultWeights().backgroundCap,
                "composite capped at traceBackgroundCap for background-only windows");
    }

    // ---------------------------------------------------------------
    // Baseline agreement / rolling divergence contract
    // ---------------------------------------------------------------

    @Test
    void poorBaselineAgreementBlocksStrongPromotion() {
        // Baselines diverge materially from each other — old-old is
        // CASSANDRA_SCHEMA_SYNC heavy, new-new is HBASE_CLIENT_MUTATION
        // heavy. The rolling lane adds schema-sync traffic too, but we
        // should not promote to STRONG just because the rolling lane
        // looks similar to one baseline while the baselines disagree.
        FlowExtractionResult oo = extract("s",
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0));
        FlowExtractionResult ro = extract("s",
                cassandraVerbTrace("SCHEMA_PULL_REQ", 8, 0));
        FlowExtractionResult nn = extract("s",
                hbaseServiceMethodTrace("ClientService", "Mutate", 10, 0));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 10, 0), traceOf("A", 10, 5), traceOf("B", 10, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 5,
                /* boundaryInvolvedRollingFlowCount */ 2,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertTrue(
                decision.baselineAgreementScore < defaultWeights().minBaselineAgreementForStrong,
                "baselines disagree below the strong threshold");
        assertNotEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength,
                "poor baseline agreement must block strong promotion");
    }

    @Test
    void goodBaselineAgreementPlusRollingDivergenceReachesStrong() {
        // Both baselines carry identical schema-sync + paxos traffic.
        // Rolling lane replaces half of the paxos with repair messages
        // (a real cross-version drift). Baseline agreement is high,
        // rolling divergence is high, support exists — STRONG.
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("REPAIR_MESSAGE", 10, 10)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 20, 0), traceOf("A", 20, 5),
                traceOf("A", 20, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertTrue(decision.supportClass
                .atLeast(TraceSupportClass.FAMILY_BACKED));
        assertTrue(
                decision.baselineAgreementScore >= defaultWeights().minBaselineAgreementForStrong,
                "baselines agree above the threshold");
        assertTrue(
                decision.rollingDivergenceScore >= defaultWeights().minRollingDivergenceForStrong,
                "rolling lane drifted from the baselines");
        assertEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength);
    }

    @Test
    void boundaryCorroborationRescuesBorderlineSupportedWindow() {
        // Moderate divergence: baselines carry SCHEMA + PAXOS, rolling
        // keeps SCHEMA but swaps some PAXOS for REPAIR. The composite
        // score lands just below traceStrongScoreThreshold without
        // boundary evidence but clears it once boundary corroboration
        // is present.
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 5, 10),
                cassandraVerbTrace("REPAIR_MESSAGE", 5, 20)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 20, 0), traceOf("A", 20, 5),
                traceOf("A", 20, 0));

        TraceWindowGuidanceScorer.Weights weights = new TraceWindowGuidanceScorer.Weights(
                1.0, 0.2, 0.4,
                /* strongScoreThreshold */ 0.50,
                /* weakScoreThreshold */ 0.10,
                /* boundaryBonus */ 0.15,
                /* orderBonusCap */ 0.05,
                /* backgroundCap */ 0.10,
                /* minBaselineAgreementForStrong */ 0.55,
                /* minRollingDivergenceForStrong */ 0.20);

        TraceWindowGuidanceDecision withoutBoundary = TraceWindowGuidanceScorer
                .score(triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                        /* windowSimInteresting */ true,
                        /* upgradedBoundaryEventCount */ 0,
                        /* boundaryInvolvedRollingFlowCount */ 0,
                        /* changedMessageCount */ 0,
                        EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION,
                        MISSING_MIN_COUNT, MISSING_MIN_FRACTION, weights);
        TraceWindowGuidanceDecision withBoundary = TraceWindowGuidanceScorer
                .score(triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                        /* windowSimInteresting */ true,
                        /* upgradedBoundaryEventCount */ 4,
                        /* boundaryInvolvedRollingFlowCount */ 1,
                        /* changedMessageCount */ 0,
                        EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION,
                        MISSING_MIN_COUNT, MISSING_MIN_FRACTION, weights);

        assertTrue(withBoundary.compositeScore > withoutBoundary.compositeScore,
                "boundary bonus must strictly increase the composite");
        assertEquals(TraceEvidenceStrength.WEAK,
                withoutBoundary.traceEvidenceStrength,
                "without boundary corroboration this window is WEAK");
        assertEquals(TraceEvidenceStrength.STRONG,
                withBoundary.traceEvidenceStrength,
                "boundary corroboration must rescue a supported mixed-version window");
    }

    @Test
    void changedMessageCorroborationRescuesBorderlineWindow() {
        // Mirror of boundaryCorroborationRescuesBorderlineSupportedWindow
        // but with boundary evidence absent and changedMessage payloads
        // present. Modes 0/2/3/4 keep changedMessage as a live
        // corroborator; the scorer must honor that contract symmetrically
        // with boundary crossings.
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 5, 10),
                cassandraVerbTrace("REPAIR_MESSAGE", 5, 20)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 20, 0), traceOf("A", 20, 5),
                traceOf("A", 20, 0));
        TraceWindowGuidanceScorer.Weights weights = new TraceWindowGuidanceScorer.Weights(
                1.0, 0.2, 0.4,
                /* strongScoreThreshold */ 0.50,
                /* weakScoreThreshold */ 0.10,
                /* boundaryBonus */ 0.15,
                /* orderBonusCap */ 0.05,
                /* backgroundCap */ 0.10,
                /* minBaselineAgreementForStrong */ 0.55,
                /* minRollingDivergenceForStrong */ 0.20);

        TraceWindowGuidanceDecision withoutCorroboration = TraceWindowGuidanceScorer
                .score(triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                        /* windowSimInteresting */ true,
                        /* upgradedBoundaryEventCount */ 0,
                        /* boundaryInvolvedRollingFlowCount */ 0,
                        /* changedMessageCount */ 0,
                        EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION,
                        MISSING_MIN_COUNT, MISSING_MIN_FRACTION, weights);
        TraceWindowGuidanceDecision withChangedMessage = TraceWindowGuidanceScorer
                .score(triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                        /* windowSimInteresting */ true,
                        /* upgradedBoundaryEventCount */ 0,
                        /* boundaryInvolvedRollingFlowCount */ 0,
                        /* changedMessageCount */ 7,
                        EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION,
                        MISSING_MIN_COUNT, MISSING_MIN_FRACTION, weights);

        assertEquals(0, withoutCorroboration.changedMessageCount);
        assertEquals(7, withChangedMessage.changedMessageCount,
                "scorer must propagate the changedMessageCount field for observability");
        assertFalse(withoutCorroboration.changedMessageGatePassed);
        assertTrue(withChangedMessage.changedMessageGatePassed,
                "changedMessageCount > 0 must clear the corroboration gate on its own");
        assertTrue(
                withChangedMessage.compositeScore > withoutCorroboration.compositeScore,
                "changedMessage corroboration bonus must strictly increase the composite");
        assertEquals(TraceEvidenceStrength.WEAK,
                withoutCorroboration.traceEvidenceStrength);
        assertEquals(TraceEvidenceStrength.STRONG,
                withChangedMessage.traceEvidenceStrength,
                "changedMessage alone must rescue a supported mixed-version window");
        assertTrue(withChangedMessage.firingReasons
                .contains("changed_message_corroborated"),
                "firing reasons must flag the changedMessage path");
    }

    // ---------------------------------------------------------------
    // Order signal
    // ---------------------------------------------------------------

    @Test
    void orderSignalRequiresSupportBeforeItCanStrengthen() {
        // Unsupported window — only BACKGROUND families are shared, and
        // rolling order is badly scrambled. The bounded order bonus
        // must NOT strengthen beyond the background cap.
        FlowExtractionResult oo = extract("s",
                cassandraVerbTrace("GOSSIP_SYN", 20, 0));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("GOSSIP_SYN", 10, 0),
                cassandraVerbTrace("GOSSIP_DIGEST_ACK", 10, 10)));
        FlowExtractionResult nn = extract("s",
                cassandraVerbTrace("GOSSIP_SYN", 20, 0));
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 20, 0), traceOf("A", 20, 5),
                traceOf("A", 20, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertEquals(TraceSupportClass.BACKGROUND_ONLY, decision.supportClass);
        assertEquals(0.0, decision.orderBonusApplied, 1e-9,
                "order bonus must be zero when support is below FLOW_BACKED");
        assertNotEquals(TraceEvidenceStrength.STRONG,
                decision.traceEvidenceStrength);
    }

    @Test
    void cassandraSchemaOrderAnomalyIsVisible() {
        // Supported schema sync with a reordered interleaving. Both
        // baselines interleave SCHEMA + PAXOS the same way; rolling
        // lane flips the order. With explicit-flow support and a
        // meaningful order divergence, the order bonus and the
        // composite score move up; the window should at minimum reach
        // WEAK (the order signal is bounded and cannot drive STRONG on
        // its own without enough baseline agreement + divergence).
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 0),
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 10)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 4, 0), traceOf("A", 4, 5), traceOf("A", 4, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertTrue(decision.orderDivergenceScore > 0.0,
                "compressed family order must differ across lanes");
        assertTrue(decision.supportClass
                .atLeast(TraceSupportClass.FAMILY_BACKED));
        // The per-family ordering anomaly alone isn't enough to reach
        // STRONG without additional divergence — but the window
        // remains visible as WEAK rather than being filtered out.
        assertNotEquals(TraceEvidenceStrength.NONE,
                decision.traceEvidenceStrength);
    }

    @Test
    void hdfsCreateThenBlockPipelineOrderingAnomalyRaisesInterestWithoutHeartbeats() {
        // Both baselines carry the namespace → block-pipeline sequence:
        // create (HDFS_CLIENT_NAMESPACE_MUTATION) → addBlock
        // (HDFS_CLIENT_BLOCK_PIPELINE). Rolling lane flips the order
        // so the compressed family sequence diverges across lanes.
        // The rolling-exclusive heartbeat chatter is BACKGROUND and
        // must not register as an order anomaly.
        FlowExtractionResult oo = extract("s", combine(
                hdfsRpcTrace("ClientProtocol", "create", 2, 0),
                hdfsRpcTrace("ClientProtocol", "addBlock", 2, 10)));
        FlowExtractionResult ro = extract("s", combine(
                hdfsRpcTrace("ClientProtocol", "addBlock", 2, 0),
                hdfsRpcTrace("ClientProtocol", "create", 2, 10),
                hdfsRpcTrace("DatanodeProtocol", "sendHeartbeat", 10, 20)));
        FlowExtractionResult nn = extract("s", combine(
                hdfsRpcTrace("ClientProtocol", "create", 2, 0),
                hdfsRpcTrace("ClientProtocol", "addBlock", 2, 10)));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 4, 0), traceOf("A", 4, 5), traceOf("A", 4, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertTrue(decision.orderDivergenceScore > 0.0);
        assertTrue(decision.upgradeCriticalInvolved);
        assertNotEquals(TraceEvidenceStrength.NONE,
                decision.traceEvidenceStrength);
        assertNotEquals(TraceEvidenceStrength.UNSUPPORTED,
                decision.traceEvidenceStrength);
    }

    @Test
    void hbaseMutationThenRegionAdminOrderingRaisesInterestWithoutStatusChurn() {
        // OO/NN: Mutate (HBASE_CLIENT_MUTATION_OR_MULTI) → OpenRegion
        // (HBASE_REGION_ADMIN_LIFECYCLE). RO flips the order to
        // OpenRegion → Mutate, and additionally produces background
        // RegionServerReport chatter that should not contribute to the
        // order anomaly.
        FlowExtractionResult oo = extract("s", combine(
                hbaseServiceMethodTrace("ClientService", "Mutate", 2, 0),
                hbaseServiceMethodTrace("AdminService", "OpenRegion", 2, 10)));
        FlowExtractionResult ro = extract("s", combine(
                hbaseServiceMethodTrace("AdminService", "OpenRegion", 2, 0),
                hbaseServiceMethodTrace("ClientService", "Mutate", 2, 10),
                hbaseServiceMethodTrace("RegionServerStatusService",
                        "RegionServerReport", 8, 20)));
        FlowExtractionResult nn = extract("s", combine(
                hbaseServiceMethodTrace("ClientService", "Mutate", 2, 0),
                hbaseServiceMethodTrace("AdminService", "OpenRegion", 2, 10)));

        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 4, 0), traceOf("A", 4, 5), traceOf("A", 4, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertTrue(decision.upgradeCriticalInvolved);
        assertTrue(decision.orderDivergenceScore > 0.0);
        assertNotEquals(TraceEvidenceStrength.NONE,
                decision.traceEvidenceStrength);
    }

    // ---------------------------------------------------------------
    // Stage gate
    // ---------------------------------------------------------------

    @Test
    void preUpgradeStageCannotReachStrongEvenWithHighScore() {
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("REPAIR_MESSAGE", 10, 10)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 10, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 10, 10)));
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 20, 0), traceOf("A", 20, 5),
                traceOf("A", 20, 0));

        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, oo, ro, nn, TraceWindow.StageKind.PRE_UPGRADE,
                /* windowSimInteresting */ true,
                /* upgradedBoundaryEventCount */ 10,
                /* boundaryInvolvedRollingFlowCount */ 4,
                /* changedMessageCount */ 0,
                EXCLUSIVE_MIN_COUNT, EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, defaultWeights());

        assertFalse(decision.stageGatePassed,
                "PRE_UPGRADE must never pass the stage gate");
        assertEquals(TraceEvidenceStrength.WEAK,
                decision.traceEvidenceStrength);
    }

    @Test
    void compositeScoreRecordsStrongAndWeakThresholds() {
        // Smoke test on the threshold fields — they must mirror the
        // Weights so offline replay can reproduce the exact cutoff.
        FlowExtractionResult empty = FlowExtractionResult.empty();
        DiffComputeMessageTriDiff.MessageTriDiffResult triDiff = triDiff(
                traceOf("A", 1, 0), traceOf("A", 1, 0), traceOf("A", 1, 0));
        TraceWindowGuidanceScorer.Weights weights = defaultWeights();
        TraceWindowGuidanceDecision decision = TraceWindowGuidanceScorer.score(
                triDiff, empty, empty, empty, TraceWindow.StageKind.POST_STAGE,
                /* windowSimInteresting */ false,
                /* upgradedBoundaryEventCount */ 0,
                /* boundaryInvolvedRollingFlowCount */ 0,
                /* changedMessageCount */ 0, EXCLUSIVE_MIN_COUNT,
                EXCLUSIVE_MIN_FRACTION, MISSING_MIN_COUNT,
                MISSING_MIN_FRACTION, weights);
        assertEquals(weights.strongScoreThreshold,
                decision.strongScoreThreshold,
                1e-9);
        assertEquals(weights.weakScoreThreshold, decision.weakScoreThreshold,
                1e-9);
    }

    // ---------------------------------------------------------------
    // Dominant order anomalous family
    // ---------------------------------------------------------------

    @Test
    void dominantOrderAnomalousFamilyIsNullWhenNoPerPairMismatch() {
        // All three lanes share the same per-(srcRole->dstRole) order
        // on upgrade-critical families. The scorer must report null so
        // the CSV column does not lie about a non-existent anomaly.
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));

        ProtocolFamily anomalous = TraceWindowGuidanceScorer
                .findDominantOrderAnomalousFamily(
                        oo.perRolePairCompressedFamilyOrder(),
                        ro.perRolePairCompressedFamilyOrder(),
                        nn.perRolePairCompressedFamilyOrder());
        assertNull(anomalous,
                "no per-role-pair mismatch must produce a null dominant order anomalous family");
    }

    @Test
    void dominantOrderAnomalousFamilyPicksRollingFamilyAtFirstMismatch() {
        // OO/NN order on the shared pair is SCHEMA -> PAXOS.
        // RO flips it to PAXOS -> SCHEMA. First mismatch on position 0
        // → rolling-side family is CASSANDRA_PAXOS.
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 0),
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 10)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));

        ProtocolFamily anomalous = TraceWindowGuidanceScorer
                .findDominantOrderAnomalousFamily(
                        oo.perRolePairCompressedFamilyOrder(),
                        ro.perRolePairCompressedFamilyOrder(),
                        nn.perRolePairCompressedFamilyOrder());
        assertEquals(ProtocolFamily.CASSANDRA_PAXOS, anomalous,
                "rolling-side family at the first per-pair mismatch must win");
    }

    @Test
    void dominantOrderAnomalousFamilyIgnoresBackgroundInterleaving() {
        // Rolling lane has the same upgrade-critical backbone as the
        // baselines but interleaves BACKGROUND gossip. After the
        // UPGRADE_CRITICAL class filter the filtered sequences are
        // identical — no anomaly on any pair.
        FlowExtractionResult oo = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));
        FlowExtractionResult ro = extract("s", combine(
                cassandraVerbTrace("GOSSIP_SYN", 2, 0),
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 10),
                cassandraVerbTrace("GOSSIP_SYN", 2, 20),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 30)));
        FlowExtractionResult nn = extract("s", combine(
                cassandraVerbTrace("SCHEMA_PULL_REQ", 2, 0),
                cassandraVerbTrace("PAXOS_PREPARE_REQ", 2, 10)));

        ProtocolFamily anomalous = TraceWindowGuidanceScorer
                .findDominantOrderAnomalousFamily(
                        oo.perRolePairCompressedFamilyOrder(),
                        ro.perRolePairCompressedFamilyOrder(),
                        nn.perRolePairCompressedFamilyOrder());
        assertNull(anomalous,
                "background reorder around a matching upgrade-critical backbone must not surface an anomaly");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Build a tri-diff over three synthetic traces. The triDiff result
     * drives the Phase 1 admission booleans; the rest of the scorer
     * consumes the FlowExtractionResults separately.
     */
    private static DiffComputeMessageTriDiff.MessageTriDiffResult triDiff(
            Trace oo,
            Trace ro, Trace nn) {
        return DiffComputeMessageTriDiff.compute(oo, ro, nn);
    }

    private static Trace traceOf(String messageType, int count, int idxOffset) {
        Trace trace = new Trace();
        for (int i = 0; i < count; i++) {
            trace.recordSend("TestHarness.fakeSend", 10000001,
                    new int[] { i + idxOffset }, messageType + "-" + i,
                    SendMeta.builder().messageType(messageType + "-" + i)
                            .build(),
                    messageType + "-" + i);
        }
        return trace;
    }

    /**
     * Build a synthetic Cassandra-style trace using the verb field the
     * Phase 1 classifier keys on. Repeats the same verb {@code count}
     * times with distinct indices.
     */
    private static Trace cassandraVerbTrace(String verb, int count,
            int idxOffset) {
        Trace trace = new Trace();
        for (int i = 0; i < count; i++) {
            trace.recordSend("CassandraHarness.fakeSend", 10000001,
                    new int[] { i + idxOffset }, verb,
                    SendMeta.builder().protocol("cassandra")
                            .nodeRole("cassandra").peerRole("cassandra")
                            .nodeId("executor-N0").peerId("executor-N1")
                            .messageType(verb).logicalMessageId(
                                    verb + "-" + (i + idxOffset))
                            .build(),
                    verb);
        }
        return trace;
    }

    /**
     * Build a synthetic HDFS RPC trace. The classifier classifies on
     * {@code rpcService} + {@code rpcMethod}; the flow extractor reads
     * these too.
     */
    private static Trace hdfsRpcTrace(String rpcService, String rpcMethod,
            int count, int idxOffset) {
        Trace trace = new Trace();
        for (int i = 0; i < count; i++) {
            trace.recordSend("HdfsHarness.fakeSend", 10000001,
                    new int[] { i + idxOffset },
                    rpcService + "#" + rpcMethod,
                    SendMeta.builder().protocol("hdfs-rpc")
                            .nodeRole("client").peerRole("namenode")
                            .nodeId("executor-N0").peerId("executor-N1")
                            .rpcService(rpcService).rpcMethod(rpcMethod)
                            .messageType(rpcMethod)
                            .logicalMessageId(rpcMethod + "-" + (i + idxOffset))
                            .build(),
                    rpcMethod);
        }
        return trace;
    }

    /**
     * Build a synthetic HBase trace keyed on protobuf service +
     * method, matching the Phase 1 HBase classifier path.
     */
    private static Trace hbaseServiceMethodTrace(String service, String method,
            int count, int idxOffset) {
        Trace trace = new Trace();
        for (int i = 0; i < count; i++) {
            trace.recordSend("HbaseHarness.fakeSend", 10000001,
                    new int[] { i + idxOffset }, service + "#" + method,
                    SendMeta.builder().protocol("hbase")
                            .nodeRole("hbase").peerRole("regionserver")
                            .nodeId("executor-N0").peerId("executor-N1")
                            .rpcService(service).rpcMethod(method)
                            .messageType(method)
                            .logicalMessageId(method + "-" + (i + idxOffset))
                            .build(),
                    method);
        }
        return trace;
    }

    private static Trace combine(Trace... traces) {
        Trace combined = new Trace();
        int idx = 0;
        for (Trace trace : traces) {
            if (trace == null) {
                continue;
            }
            for (org.zlab.net.tracker.TraceEntry entry : trace
                    .getTraceEntries()) {
                // Replay each entry into the combined trace via the
                // runtime's recordSend so every derived field
                // (canonicalKey, family classification) stays
                // consistent with the rest of the harness.
                SendMeta.Builder builder = SendMeta.builder();
                if (entry.protocol != null) {
                    builder.protocol(entry.protocol);
                }
                if (entry.nodeId != null) {
                    builder.nodeId(entry.nodeId);
                }
                if (entry.peerId != null) {
                    builder.peerId(entry.peerId);
                }
                if (entry.nodeRole != null) {
                    builder.nodeRole(entry.nodeRole);
                }
                if (entry.peerRole != null) {
                    builder.peerRole(entry.peerRole);
                }
                if (entry.rpcService != null) {
                    builder.rpcService(entry.rpcService);
                }
                if (entry.rpcMethod != null) {
                    builder.rpcMethod(entry.rpcMethod);
                }
                if (entry.messageKind != null) {
                    builder.messageKind(entry.messageKind);
                }
                if (entry.messageType != null) {
                    builder.messageType(entry.messageType);
                }
                if (entry.logicalMessageId != null) {
                    builder.logicalMessageId(entry.logicalMessageId);
                }
                if (entry.deliveryId != null) {
                    builder.deliveryId(entry.deliveryId);
                }
                combined.recordSend("CombineHarness.fakeSend", 10000001,
                        new int[] { idx++ }, entry.messageType,
                        builder.build(), entry.messageType);
            }
        }
        return combined;
    }

    private static FlowExtractionResult extract(String stage, Trace trace) {
        if (trace == null) {
            return FlowExtractionResult.empty();
        }
        return TraceFlowExtractor.extract(stage, trace, BoundaryOracle.UNKNOWN);
    }

    /** Suppress an unused-import warning for a field we keep for future use. */
    @SuppressWarnings("unused")
    private static final Object KEEP_PROTOCOL_FAMILY_IMPORT = ProtocolFamily.UNKNOWN;

    /** Suppress an unused-import warning for Collections. */
    @SuppressWarnings("unused")
    private static final Object KEEP_COLLECTIONS_IMPORT = Collections
            .emptyList();
}
