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
import org.zlab.upfuzz.fuzzingengine.server.observability.AdmissionReason;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceEvidenceStrength;
import org.zlab.upfuzz.fuzzingengine.server.observability.TraceMetadataCoverageRow;
import org.zlab.upfuzz.fuzzingengine.trace.BoundaryResolutionResult;
import org.zlab.upfuzz.fuzzingengine.trace.ResolvedTraceEndpoint;
import org.zlab.upfuzz.fuzzingengine.trace.TopologyNormalizer;
import org.zlab.upfuzz.fuzzingengine.trace.TopologySnapshot;

/**
 * Regression tests for {@link FuzzingServer}'s non-scorer helpers. The
 * Phase 1 / Phase 2 window-decision path is now covered by
 * {@link TraceWindowGuidanceScorerTest} (since Phase 3 replaced
 * {@code evaluateTriDiffWindow} with {@link TraceWindowGuidanceScorer}).
 *
 * <p>What this file still covers:
 * <ul>
 * <li>Admission-reason classifier ({@link FuzzingServer#classifyAdmissionReason}).</li>
 * <li>Round-level strength aggregation
 * ({@link FuzzingServer#classifyRoundTraceEvidenceStrength}) and the
 * Phase 3 {@link FuzzingServer#isTraceAdmissible} gate.</li>
 * <li>Boundary-resolution bookkeeping
 * ({@link FuzzingServer#countUpgradedBoundaryCrossings} /
 * {@link FuzzingServer#countUpgradedBoundaryCrossingsDetailed}) and the
 * {@link TopologyNormalizer} / {@link TopologySnapshot} alias
 * handling.</li>
 * <li>{@code trace_metadata_coverage.csv} row classification.</li>
 * <li>Mode-5 / mode-6 rolling-trace config surface (including the Phase 0
 * mode-5 retirement of {@code changedMessage} corroboration).</li>
 * </ul>
 */
class FuzzingServerTriDiffDecisionTest {

    // ---------------------------------------------------------------
    // Tri-diff accessor invariants
    // ---------------------------------------------------------------

    @Test
    void fractionsRemainBoundedEvenForAsymmetricLanes() {
        // Sanity check on the boundedness invariant. Previous normalization
        // divided missing by rolling-lane size, which blew past 1.0 whenever
        // rolling was much smaller than the baselines. The accessors must
        // return values in [0,1].
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
        // and assert TRACE_ONLY_TRIDIFF_MISSING is impossible. Missing-only
        // is observability-only, never a primary admission reason.
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
    // Round-level trace strength roll-up (Phase 3)
    // ---------------------------------------------------------------

    @Test
    void roundLevelStrengthIsNoneWhenTraceInterestingFalse() {
        assertEquals(TraceEvidenceStrength.NONE,
                FuzzingServer.classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ false,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 0,
                        /* unsupportedButRepeatableFiringWindows */ 0,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false));
    }

    @Test
    void roundLevelStrengthPreferStrongestWindow() {
        assertEquals(TraceEvidenceStrength.STRONG,
                FuzzingServer.classifyRoundTraceEvidenceStrength(
                        true, 1, 1, 1, 1, true));
        assertEquals(TraceEvidenceStrength.WEAK,
                FuzzingServer.classifyRoundTraceEvidenceStrength(
                        true, 0, 1, 1, 1, true));
        assertEquals(TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                FuzzingServer.classifyRoundTraceEvidenceStrength(
                        true, 0, 0, 1, 1, true));
        assertEquals(TraceEvidenceStrength.UNSUPPORTED,
                FuzzingServer.classifyRoundTraceEvidenceStrength(
                        true, 0, 0, 0, 1, true));
        assertEquals(TraceEvidenceStrength.WEAK,
                FuzzingServer.classifyRoundTraceEvidenceStrength(
                        true, 0, 0, 0, 0, true));
        assertEquals(TraceEvidenceStrength.NONE,
                FuzzingServer.classifyRoundTraceEvidenceStrength(
                        true, 0, 0, 0, 0, false));
    }

    @Test
    void isTraceAdmissiblePhase4Policy() {
        // Phase 4 admission policy (default
        // enableLowerConfidenceTraceAdmission=true):
        // STRONG → admit regardless of support class
        // UNSUPPORTED_BUT_REPEATABLE → admit (repeatable rolling-
        // only upgrade-critical traffic)
        // WEAK with FLOW_BACKED+ support → admit
        // WEAK with weaker support → reject
        // UNSUPPORTED → reject
        // traceInteresting=false → reject regardless of strength
        Config.Configuration cfg = new Config.Configuration();
        cfg.enableLowerConfidenceTraceAdmission = true;
        Config.setInstance(cfg);

        assertTrue(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.STRONG,
                TraceSupportClass.UNSUPPORTED));
        assertTrue(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                TraceSupportClass.UNSUPPORTED));
        assertTrue(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.WEAK,
                TraceSupportClass.FLOW_BACKED));
        assertTrue(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.WEAK,
                TraceSupportClass.FULL));
        assertFalse(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.WEAK,
                TraceSupportClass.BACKGROUND_ONLY),
                "WEAK with only background support stays out of "
                        + "the admission path");
        assertFalse(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.WEAK,
                TraceSupportClass.FAMILY_BACKED),
                "WEAK with family-only support stays out — flow-level "
                        + "support is the Phase 4 floor");
        assertFalse(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.UNSUPPORTED,
                TraceSupportClass.UNSUPPORTED));
        assertFalse(FuzzingServer.isTraceAdmissible(false,
                TraceEvidenceStrength.STRONG,
                TraceSupportClass.FLOW_BACKED),
                "traceInteresting=false blocks admission even at STRONG");
    }

    @Test
    void phase4AdmissionCanBeDisabledForRollback() {
        Config.Configuration cfg = new Config.Configuration();
        cfg.enableLowerConfidenceTraceAdmission = false;
        Config.setInstance(cfg);
        assertTrue(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.STRONG,
                TraceSupportClass.FLOW_BACKED));
        assertFalse(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                TraceSupportClass.FLOW_BACKED),
                "knob off → Phase 3 STRONG-only policy is restored");
        assertFalse(FuzzingServer.isTraceAdmissible(true,
                TraceEvidenceStrength.WEAK,
                TraceSupportClass.FLOW_BACKED));
    }

    @Test
    void admissionChainBlocksWeakUnsupportedFromUpgradingBranchOnly() {
        // Same chain the server uses inside updateStatus: round strength ->
        // isTraceAdmissible -> classifyAdmissionReason. A WEAK round
        // without flow-level support still must NOT promote BRANCH_ONLY
        // to BRANCH_AND_TRACE.
        Config.Configuration cfg = new Config.Configuration();
        cfg.enableLowerConfidenceTraceAdmission = true;
        Config.setInstance(cfg);

        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 1,
                        /* unsupportedButRepeatableFiringWindows */ 0,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.WEAK, roundStrength);

        boolean effective = FuzzingServer.isTraceAdmissible(true,
                roundStrength, TraceSupportClass.BACKGROUND_ONLY);
        assertFalse(effective);

        AdmissionReason reasonWithBranch = FuzzingServer
                .classifyAdmissionReason(
                        /* newBranchCoverage */ true,
                        effective,
                        /* triDiffExclusiveFired */ true,
                        /* windowSimFired */ false,
                        /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_ONLY, reasonWithBranch,
                "weak+unsupported trace must not upgrade BRANCH_ONLY");
    }

    @Test
    void admissionChainRoutesWeakFlowBackedToBranchAndWeakTrace() {
        // Phase 4 extension: a WEAK round with flow-backed support IS
        // admissible. With branch coverage the round surfaces as
        // BRANCH_AND_TRACE (admission reason); the queue-priority
        // classifier then routes it into BRANCH_AND_WEAK_TRACE because
        // the strength is still non-STRONG.
        Config.Configuration cfg = new Config.Configuration();
        cfg.enableLowerConfidenceTraceAdmission = true;
        Config.setInstance(cfg);

        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 1,
                        /* unsupportedButRepeatableFiringWindows */ 0,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.WEAK, roundStrength);

        boolean effective = FuzzingServer.isTraceAdmissible(true,
                roundStrength, TraceSupportClass.FLOW_BACKED);
        assertTrue(effective,
                "WEAK with flow-level support must be admissible under "
                        + "Phase 4 lower-confidence admission");

        AdmissionReason reasonWithBranch = FuzzingServer
                .classifyAdmissionReason(
                        /* newBranchCoverage */ true,
                        effective,
                        /* triDiffExclusiveFired */ true,
                        /* windowSimFired */ false,
                        /* aggregateSimFired */ false);
        assertEquals(AdmissionReason.BRANCH_AND_TRACE, reasonWithBranch,
                "branch + supported weak trace surfaces as BRANCH_AND_TRACE");

        org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass priority = FuzzingServer
                .classifyQueuePriorityClass(reasonWithBranch, roundStrength);
        assertEquals(
                org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass.BRANCH_AND_WEAK_TRACE,
                priority,
                "non-STRONG BRANCH_AND_TRACE maps to BRANCH_AND_WEAK_TRACE");
        assertEquals(
                org.zlab.upfuzz.fuzzingengine.server.observability.SchedulerClass.SHADOW_EVAL,
                TestPlanCorpus.mapToSchedulerClass(priority),
                "branch+supported-weak-trace ultimately lands in SHADOW_EVAL");
    }

    @Test
    void admissionChainPromotesStrongTraceToBranchAndTrace() {
        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 2,
                        /* weakFiringWindows */ 1,
                        /* unsupportedButRepeatableFiringWindows */ 0,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.STRONG, roundStrength);

        boolean effective = FuzzingServer.isTraceAdmissible(true,
                roundStrength);
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

    @Test
    void admissionChainRoutesUnsupportedButRepeatableToShadow() {
        // Phase 4 change: UNSUPPORTED_BUT_REPEATABLE is now
        // admissible (the "repeated rolling-only upgrade-critical
        // traffic" pattern the Apr16 HDFS 2.10.2 -> 3.3.6 runs
        // produced). The round surfaces as a trace-only admission
        // whose priority class is TRACE_ONLY_WEAK — routed by
        // TestPlanCorpus.mapToSchedulerClass into SHADOW_EVAL.
        Config.Configuration cfg = new Config.Configuration();
        cfg.enableLowerConfidenceTraceAdmission = true;
        Config.setInstance(cfg);

        TraceEvidenceStrength roundStrength = FuzzingServer
                .classifyRoundTraceEvidenceStrength(
                        /* traceInteresting */ true,
                        /* strongFiringWindows */ 0,
                        /* weakFiringWindows */ 0,
                        /* unsupportedButRepeatableFiringWindows */ 1,
                        /* unsupportedFiringWindows */ 0,
                        /* aggregateSimFired */ false);
        assertEquals(TraceEvidenceStrength.UNSUPPORTED_BUT_REPEATABLE,
                roundStrength);
        assertTrue(FuzzingServer.isTraceAdmissible(true, roundStrength,
                TraceSupportClass.UNSUPPORTED),
                "unsupported-but-repeatable must be admissible under "
                        + "Phase 4");

        AdmissionReason reason = FuzzingServer.classifyAdmissionReason(
                /* newBranchCoverage */ false,
                /* traceInteresting */ true,
                /* triDiffExclusiveFired */ false,
                /* windowSimFired */ true,
                /* aggregateSimFired */ false);
        org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass queueClass = FuzzingServer
                .classifyQueuePriorityClass(reason, roundStrength);
        assertEquals(
                org.zlab.upfuzz.fuzzingengine.server.observability.QueuePriorityClass.TRACE_ONLY_WEAK,
                queueClass,
                "UNSUPPORTED_BUT_REPEATABLE is non-STRONG → routes to "
                        + "TRACE_ONLY_WEAK → SHADOW_EVAL");
        assertEquals(
                org.zlab.upfuzz.fuzzingengine.server.observability.SchedulerClass.SHADOW_EVAL,
                TestPlanCorpus.mapToSchedulerClass(queueClass));
    }

    // ---------------------------------------------------------------
    // Boundary counting
    // ---------------------------------------------------------------

    @Test
    void boundaryCrossingIgnoresNonUpgradedWithinVersionTraffic() {
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
    // Topology-aware boundary resolution (Phase 0)
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
        assertEquals(3, detailed.indexResolvedEndpointCount);
        assertEquals(1, detailed.unresolvedEndpointCount,
                "IP outside the registered cluster is unresolved");
    }

    @Test
    void boundaryCrossingCountsUniqueRoleResolution() {
        TopologySnapshot topology = buildTopology(
                "datanode|DC3N0",
                "datanode|DC3N1",
                "namenode|DC3N2");
        Trace merged = new Trace();
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
        TopologySnapshot topology = buildTopology(
                "cassandra|DC3N0|10.0.0.1",
                "cassandra|DC3N1|10.0.0.2",
                "namenode|nn.internal");

        Trace merged = new Trace();
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
        merged.recordSend("metaCov.fakeSend", 3, new int[] { 0 },
                "m3",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .peerId("mystery-host")
                        .nodeRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m3");
        merged.recordSend("metaCov.fakeSend", 4, new int[] { 0 },
                "m4",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .nodeRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m4");
        merged.recordSend("metaCov.fakeSend", 5, new int[] { 0 },
                "m5",
                SendMeta.builder()
                        .nodeId("executor-N0")
                        .peerId("")
                        .nodeRole("cassandra")
                        .messageType("UnitMessage")
                        .build(),
                "m5");
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

        String header = TraceMetadataCoverageRow.csvHeader();
        assertTrue(header.contains("entries_with_missing_peer_id"));
        assertTrue(header.contains("entries_with_role_ambiguous_peer_id"));
        assertTrue(header.contains("entries_with_unresolved_peer_id"));
    }

    @Test
    void metadataCoverageRowHandlesNullTraceAndNullSnapshot() {
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
        TopologySnapshot topology = buildTopology(
                "datanode|DC3N0|10.0.0.1",
                "datanode|DC3N1|10.0.0.2",
                "namenode|DC3N2|10.0.0.3");
        Trace merged = new Trace();
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
    // Mode-5 / Mode-6 config helpers
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
        assertFalse(conf.useTrace);
        assertFalse(conf.useCanonicalTraceSimilarity);
        assertFalse(conf.useCanonicalMessageIdentityDiff);
    }

    @Test
    void nonRollingModesKeepChangedMessageCorroboration() {
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
            Trace oo, Trace ro, Trace nn) {
        return DiffComputeMessageTriDiff.compute(oo, ro, nn);
    }

    private static Trace traceOf(String... messages) {
        Trace trace = new Trace();
        int idx = 0;
        for (String message : messages) {
            trace.recordSend("TriDiffDecisionTest.fakeSend", 10000001,
                    new int[] { idx }, message,
                    SendMeta.builder().messageType(message).build(),
                    message);
            idx++;
        }
        return trace;
    }
}
