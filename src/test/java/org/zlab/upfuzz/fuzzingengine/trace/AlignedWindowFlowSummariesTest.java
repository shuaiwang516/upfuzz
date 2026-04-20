package org.zlab.upfuzz.fuzzingengine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.TraceEntry;
import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.flow.FlowBoundaryStatus;
import org.zlab.net.tracker.flow.FlowExtractionResult;

/**
 * Phase 2 integration tests for {@link AlignedWindowFlowSummaries} and
 * {@link RollingFlowBoundaryOracle}. These live in upfuzz-shuai because
 * they exercise the topology-aware boundary classifier that depends on
 * {@link TopologySnapshot}.
 */
public class AlignedWindowFlowSummariesTest {

    private static TopologySnapshot twoNodeSnapshot() {
        TopologyNormalizer normalizer = new TopologyNormalizer();
        normalizer.registerNode(0, "namenode", "namenode-host", "10.0.0.1",
                "executor-N0");
        normalizer.registerNode(1, "datanode", "datanode-host", "10.0.0.2",
                "executor-N1");
        return normalizer.snapshot();
    }

    private static TraceEntry sendEntry(String nodeId, String peerId,
            String nodeRole, String peerRole, String protocol,
            String rpcService, String rpcMethod, String messageType,
            String logicalMessageId, long tsNanos) {
        return new TraceEntry(1, "send", 1, TraceEntry.EventType.SEND, false,
                System.currentTimeMillis(), tsNanos, nodeId, peerId, nodeRole,
                peerRole, null, protocol, messageType, null, rpcService,
                rpcMethod, null, logicalMessageId, null, null, -1, 0L, 0L, null,
                null, false, 0L, null, 0L, null, null);
    }

    private static TraceEntry recvEntry(String nodeId, String peerId,
            String nodeRole, String peerRole, String protocol,
            String rpcService, String rpcMethod, String messageType,
            String logicalMessageId, long tsNanos) {
        return new TraceEntry(1, "recv", 1, TraceEntry.EventType.RECV_BEGIN,
                false, System.currentTimeMillis(), tsNanos, nodeId, peerId,
                nodeRole, peerRole, null, protocol, messageType, null,
                rpcService, rpcMethod, null, logicalMessageId, null, null, -1,
                0L, 0L, null, null, false, 0L, null, 0L, null, null);
    }

    private static TraceWindow windowFromTrace(String stage, int ordinal,
            Set<Integer> upgradedNodeSet, Trace... nodeTraces) {
        return new TraceWindow(ordinal, "open", "close", -1, stage,
                TraceWindow.StageKind.POST_STAGE, Collections.emptySet(),
                upgradedNodeSet, "oldVer->newVer", true, nodeTraces);
    }

    private static Trace traceOf(TraceEntry... entries) {
        Trace trace = new Trace();
        for (TraceEntry entry : entries) {
            trace.addEntry(entry);
        }
        return trace;
    }

    @Test
    public void rollingBoundaryOracle_detectsCrossingViaTopologySnapshot() {
        TopologySnapshot snapshot = twoNodeSnapshot();
        Set<Integer> upgraded = new HashSet<>();
        upgraded.add(1); // N1 has been upgraded

        TraceEntry crossing = sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null,
                "call-1", 1L);
        RollingFlowBoundaryOracle oracle = new RollingFlowBoundaryOracle(
                upgraded, snapshot);
        assertEquals(FlowBoundaryStatus.CROSSING, oracle.classify(crossing));

        TraceEntry sameSide = sendEntry("10.0.0.1", "10.0.0.1", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-2",
                2L);
        assertEquals(FlowBoundaryStatus.NONE, oracle.classify(sameSide));
    }

    @Test
    public void rollingBoundaryOracle_unresolvedPeer_staysUnresolved() {
        TopologySnapshot snapshot = twoNodeSnapshot();
        Set<Integer> upgraded = Collections.singleton(1);
        TraceEntry unknownPeer = sendEntry("10.0.0.1", "203.0.113.9", null,
                null, "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null,
                "call-1", 1L);
        RollingFlowBoundaryOracle oracle = new RollingFlowBoundaryOracle(
                upgraded, snapshot);
        assertEquals(FlowBoundaryStatus.UNRESOLVED,
                oracle.classify(unknownPeer));
    }

    @Test
    public void summaries_forMatchingLanes_areReusedForMultiplePasses() {
        TopologySnapshot snapshot = twoNodeSnapshot();
        Set<Integer> upgraded = Collections.singleton(1);
        Trace oo = traceOf(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-1",
                1L));
        Trace ro = traceOf(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-1",
                1L));
        Trace nn = traceOf(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-1",
                1L));
        TraceWindow oow = windowFromTrace("POST_STAGE_1", 0,
                Collections.emptySet(), oo);
        TraceWindow row = windowFromTrace("POST_STAGE_1", 0, upgraded, ro);
        TraceWindow nnw = windowFromTrace("POST_STAGE_1", 0,
                Collections.emptySet(), nn);
        AlignedWindowFlowSummaries bundle = AlignedWindowFlowSummaries
                .from(oow, row, nnw, upgraded, snapshot);

        assertEquals(1, bundle.rolling().flowCount());
        assertEquals(1, bundle.oldOld().flowCount());
        assertEquals(1, bundle.newNew().flowCount());
        // Rolling flow crossed the upgraded boundary exactly once.
        assertEquals(1, bundle.rolling().boundaryInvolvedFlowCount());
        // Baseline lanes never mark boundary involvement because the
        // oracle passed to them is NO_BOUNDARY.
        assertEquals(0, bundle.oldOld().boundaryInvolvedFlowCount());
        assertEquals(0, bundle.newNew().boundaryInvolvedFlowCount());
    }

    @Test
    public void divergentFamilies_areRankedByGapAgainstBaseline() {
        TopologySnapshot snapshot = twoNodeSnapshot();
        Set<Integer> upgraded = Collections.singleton(1);

        // Rolling lane has 5 mkdirs flows (each unique logicalId → each
        // is its own flow, so familyMultiset counts events). OO/NN have
        // 1 mkdirs flow each. Gap on HDFS_CLIENT_NAMESPACE_MUTATION = 4.
        Trace ro = new Trace();
        for (int i = 0; i < 5; i++) {
            ro.addEntry(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                    "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null,
                    "call-r" + i, i));
        }
        Trace oo = traceOf(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-o1",
                1L));
        Trace nn = traceOf(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-n1",
                1L));
        TraceWindow oow = windowFromTrace("POST_STAGE_1", 0,
                Collections.emptySet(), oo);
        TraceWindow row = windowFromTrace("POST_STAGE_1", 0, upgraded, ro);
        TraceWindow nnw = windowFromTrace("POST_STAGE_1", 0,
                Collections.emptySet(), nn);
        AlignedWindowFlowSummaries bundle = AlignedWindowFlowSummaries
                .from(oow, row, nnw, upgraded, snapshot);

        List<AlignedWindowFlowSummaries.DivergentFamily> top = bundle
                .topDivergentFamilies(3);
        assertFalse(top.isEmpty());
        AlignedWindowFlowSummaries.DivergentFamily first = top.get(0);
        assertEquals(ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION,
                first.family);
        assertEquals(5, first.rollingCount);
        assertEquals(1, first.oldOldCount);
        assertEquals(1, first.newNewCount);
        // Rolling (5) vs baseline mean (1) gap=4.
        assertEquals(4.0, first.gap());
    }

    @Test
    public void divergentFamilies_returnEmpty_whenAllLanesMatch() {
        TopologySnapshot snapshot = twoNodeSnapshot();
        Set<Integer> upgraded = Collections.emptySet();
        Trace trace = traceOf(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-1",
                1L));
        TraceWindow oow = windowFromTrace("POST_STAGE_1", 0, upgraded, trace);
        TraceWindow row = windowFromTrace("POST_STAGE_1", 0, upgraded, trace);
        TraceWindow nnw = windowFromTrace("POST_STAGE_1", 0, upgraded, trace);
        AlignedWindowFlowSummaries bundle = AlignedWindowFlowSummaries
                .from(oow, row, nnw, upgraded, snapshot);

        assertTrue(bundle.topDivergentFamilies(3).isEmpty());
    }

    @Test
    public void extractorHonorsNullWindows() {
        TopologySnapshot snapshot = twoNodeSnapshot();
        AlignedWindowFlowSummaries bundle = AlignedWindowFlowSummaries
                .from(null, null, null, Collections.<Integer> emptySet(),
                        snapshot);
        assertEquals(0, bundle.oldOld().flowCount());
        assertEquals(0, bundle.rolling().flowCount());
        assertEquals(0, bundle.newNew().flowCount());
    }

    @Test
    public void rollingOracle_withoutSnapshot_fallsBackToNumericParsing() {
        // Legacy unit-test path: no TopologySnapshot is threaded. The
        // oracle must still return CROSSING when both endpoints parse as
        // opposite-side numeric indices so Phase 0 parity is maintained.
        Set<Integer> upgraded = Collections.singleton(1);
        RollingFlowBoundaryOracle oracle = new RollingFlowBoundaryOracle(
                upgraded, null);
        TraceEntry crossing = sendEntry("N0", "N1", null, null, "hdfs-rpc",
                "ClientNamenodeProtocol", "mkdirs", null, "call-x", 1L);
        assertEquals(FlowBoundaryStatus.CROSSING, oracle.classify(crossing));

        TraceEntry notParsable = sendEntry("host-a", "host-b", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-y",
                2L);
        assertEquals(FlowBoundaryStatus.UNRESOLVED,
                oracle.classify(notParsable));
    }

    @Test
    public void summaries_keepBaselinesFreeOfBoundaryMarks() {
        TopologySnapshot snapshot = twoNodeSnapshot();
        Set<Integer> upgraded = Collections.singleton(1);
        Trace trace = traceOf(sendEntry("10.0.0.1", "10.0.0.2", null, null,
                "hdfs-rpc", "ClientNamenodeProtocol", "mkdirs", null, "call-1",
                1L));
        TraceWindow oow = windowFromTrace("POST_STAGE_1", 0,
                Collections.emptySet(), trace);
        TraceWindow row = windowFromTrace("POST_STAGE_1", 0, upgraded, trace);
        TraceWindow nnw = windowFromTrace("POST_STAGE_1", 0,
                Collections.emptySet(), trace);
        AlignedWindowFlowSummaries bundle = AlignedWindowFlowSummaries
                .from(oow, row, nnw, upgraded, snapshot);

        FlowExtractionResult oo = bundle.oldOld();
        FlowExtractionResult nn = bundle.newNew();
        // Both baselines ran through NO_BOUNDARY — every flow is NONE.
        for (org.zlab.net.tracker.flow.TraceFlowSummary flow : oo.flows()) {
            assertEquals(FlowBoundaryStatus.NONE, flow.boundaryStatus());
        }
        for (org.zlab.net.tracker.flow.TraceFlowSummary flow : nn.flows()) {
            assertEquals(FlowBoundaryStatus.NONE, flow.boundaryStatus());
        }
        // Rolling still saw a crossing.
        assertNotEquals(0, bundle.rolling().boundaryInvolvedFlowCount());
    }
}
