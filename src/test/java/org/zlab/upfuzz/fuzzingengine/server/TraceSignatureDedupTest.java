package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.zlab.net.tracker.SendMeta;
import org.zlab.net.tracker.CanonicalKeyMode;
import org.zlab.net.tracker.Trace;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.FeedBack;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanDiffFeedbackPacket;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanFeedbackPacket;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;
import org.zlab.upfuzz.fuzzingengine.trace.WindowedTrace;

/**
 * Phase 4 regression tests for trace-signature dedup.
 *
 * <p>The Apr 12 campaigns showed that mode 5 repeatedly admitted trace-only
 * seeds carrying the same divergence shape. These tests lock in the new
 * bounded recent-signature index:
 *
 * <ul>
 * <li>repeated trace-only signatures are suppressed once saturated;</li>
 * <li>branch-backed seeds bypass signature suppression entirely;</li>
 * <li>the bounded index forgets old entries via both lookback expiry and
 * capacity eviction;</li>
 * <li>a single admitted seed contributes at most one copy of a given
 * signature.</li>
 * </ul>
 */
class TraceSignatureDedupTest {

    @Test
    void repeatedTraceOnlyWindowsWithSameSignatureAreSuppressed() {
        RecentTraceSignatureIndex index = new RecentTraceSignatureIndex(
                /*saturationThreshold*/ 1,
                /*lookbackRounds*/ 100,
                /*capacity*/ 16);
        TraceSignature signature = signature("POST_STAGE|nodes=[0]");

        assertFalse(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                /*newBranchCoverage*/ false,
                Collections.singletonList(signature),
                /*round*/ 10,
                index));

        index.recordAdmitted(Collections.singletonList(signature), 10);

        assertTrue(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                /*newBranchCoverage*/ false,
                Collections.singletonList(signature),
                /*round*/ 11,
                index));
        assertEquals(1L, index.getTotalSuppressedDuplicates());
    }

    @Test
    void branchBackedSeedsWithSameSignatureAreStillAllowed() {
        RecentTraceSignatureIndex index = new RecentTraceSignatureIndex(
                /*saturationThreshold*/ 1,
                /*lookbackRounds*/ 100,
                /*capacity*/ 16);
        TraceSignature signature = signature("POST_STAGE|nodes=[1]");
        index.recordAdmitted(Collections.singletonList(signature), 0);

        assertFalse(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                /*newBranchCoverage*/ true,
                Collections.singletonList(signature),
                /*round*/ 1,
                index));
        assertEquals(0L, index.getTotalSuppressedDuplicates());
    }

    @Test
    void lookbackExpiresOldSignatures() {
        RecentTraceSignatureIndex index = new RecentTraceSignatureIndex(
                /*saturationThreshold*/ 1,
                /*lookbackRounds*/ 2,
                /*capacity*/ 16);
        TraceSignature signature = signature("PRE_UPGRADE|nodes=[]");
        index.recordAdmitted(Collections.singletonList(signature), 0);

        assertTrue(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                false,
                Collections.singletonList(signature),
                1,
                index));
        assertFalse(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                false,
                Collections.singletonList(signature),
                2,
                index));
        assertEquals(1L, index.getTotalLookbackEvictions());
        assertEquals(0, index.getLiveCountForTest(signature, 2));
    }

    @Test
    void capacityEvictsOldestEntries() {
        RecentTraceSignatureIndex index = new RecentTraceSignatureIndex(
                /*saturationThreshold*/ 1,
                /*lookbackRounds*/ 100,
                /*capacity*/ 2);
        TraceSignature signatureA = signature("POST_STAGE|nodes=[0]");
        TraceSignature signatureB = signature("POST_STAGE|nodes=[1]");
        TraceSignature signatureC = signature("POST_STAGE|nodes=[2]");

        index.recordAdmitted(Collections.singletonList(signatureA), 0);
        index.recordAdmitted(Collections.singletonList(signatureB), 1);
        index.recordAdmitted(Collections.singletonList(signatureC), 2);

        assertEquals(2, index.recentEntryCount());
        assertEquals(1L, index.getTotalCapacityEvictions());
        assertFalse(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                false,
                Collections.singletonList(signatureA),
                3,
                index));
        assertTrue(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                false,
                Collections.singletonList(signatureB),
                3,
                index));
        assertTrue(FuzzingServer.shouldSuppressTraceOnlyBySignature(
                false,
                Collections.singletonList(signatureC),
                3,
                index));
    }

    @Test
    void duplicateWindowsInsideOneSeedOnlyRecordOnce() {
        RecentTraceSignatureIndex index = new RecentTraceSignatureIndex(
                /*saturationThreshold*/ 2,
                /*lookbackRounds*/ 100,
                /*capacity*/ 16);
        TraceSignature signature = signature("POST_FINAL_STAGE|nodes=[0, 1]");

        index.recordAdmitted(Arrays.asList(signature, signature), 5);

        assertEquals(1, index.recentEntryCount());
        assertEquals(1, index.getLiveCountForTest(signature, 5));
    }

    @Test
    void modeFiveReplayRecordsSuppressionCounterForRepeatedTraceOnlyRounds()
            throws Exception {
        Path verificationDir = Paths.get("build", "test-verification",
                "phase4-trace-signature-replay");

        new Config();
        Config.getConf().system = "cassandra";
        Config.getConf().originalVersion = "apache-cassandra-4.1.10";
        Config.getConf().upgradedVersion = "apache-cassandra-5.0.6";
        Config.getConf().configDir = "configtests";
        Config.getConf().failureDir = verificationDir.toString();
        Config.getConf().testingMode = 5;
        Config.getConf().differentialExecution = true;
        Config.getConf().useTrace = true;
        Config.getConf().useBranchCoverage = true;
        Config.getConf().useFormatCoverage = false;
        Config.getConf().useCanonicalTraceSimilarity = true;
        Config.getConf().useCanonicalMessageIdentityDiff = true;
        Config.getConf().enableObservabilityArtifacts = true;
        Config.getConf().useTraceProbation = true;
        Config.getConf().useTraceSignatureDedup = true;
        Config.getConf().traceSignatureSaturationThreshold = 1;
        Config.getConf().traceOnlyAdmissionCapPerRound = 10;
        Config.getConf().traceOnlyAdmissionCapPer100Rounds = 100;
        Config.getConf().rollingCorpusMaxSize = 32;
        Config.getConf().traceOnlyCorpusMaxShare = 1.0;

        FuzzingServer server = new FuzzingServer();
        putReplayTestPlan(server, 0);
        putReplayTestPlan(server, 1);

        server.updateStatus(repeatedTraceOnlyPacket(0));
        assertEquals(0L, server.traceSignatureIndex
                .getTotalSuppressedDuplicates());
        assertEquals(1, server.traceSignatureIndex.liveSignatureCount());
        assertEquals(1, server.testPlanCorpus.queue.size());

        server.updateStatus(repeatedTraceOnlyPacket(1));
        assertEquals(1L, server.traceSignatureIndex
                .getTotalSuppressedDuplicates());
        assertEquals(1, server.traceSignatureIndex.liveSignatureCount());
        assertEquals(1, server.traceSignatureIndex.recentEntryCount());
        assertEquals(1, server.testPlanCorpus.queue.size());

        Path summaryCsv = verificationDir.resolve("observability")
                .resolve("trace_admission_summary.csv");
        assertTrue(Files.exists(summaryCsv));

        java.util.List<String> rows = Files.readAllLines(summaryCsv);
        assertEquals(3, rows.size());

        Map<String, Integer> header = csvHeaderIndex(rows.get(0));
        String[] first = rows.get(1).split(",", -1);
        String[] second = rows.get(2).split(",", -1);

        assertEquals("true", first[header.get("admitted")]);
        assertEquals("TRACE_ONLY_TRIDIFF_EXCLUSIVE",
                first[header.get("admission_reason")]);
        assertEquals("false",
                first[header.get("trace_signature_suppressed")]);
        assertEquals("0", first[header.get(
                "cumulative_trace_signature_suppressions")]);

        assertEquals("false", second[header.get("admitted")]);
        assertEquals("UNKNOWN", second[header.get("admission_reason")]);
        assertEquals("true",
                second[header.get("trace_signature_suppressed")]);
        assertEquals("1", second[header.get(
                "cumulative_trace_signature_suppressions")]);
    }

    private static TraceSignature signature(String stageKey) {
        return new TraceSignature(
                stageKey,
                CanonicalKeyMode.SEMANTIC_SHAPE_SUMMARY,
                Collections.singletonList("send|type=A"),
                Collections.singletonList("recv|type=B"),
                TraceSignature.NO_SIMILARITY_BUCKET);
    }

    private static void putReplayTestPlan(FuzzingServer server,
            int testPacketId)
            throws Exception {
        Field field = FuzzingServer.class.getDeclaredField("testID2TestPlan");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, TestPlan> testPlans = (Map<Integer, TestPlan>) field
                .get(server);
        testPlans.put(testPacketId, new TestPlan(
                2,
                new LinkedList<>(),
                new LinkedList<>(),
                new LinkedList<>()));
    }

    private static TestPlanDiffFeedbackPacket repeatedTraceOnlyPacket(
            int testPacketId) {
        Trace baseline = traceOf(
                "shared_1",
                "shared_2",
                "shared_3",
                "shared_4",
                "shared_5");
        Trace rolling = traceOf(
                "shared_1",
                "shared_2",
                "shared_3",
                "shared_4",
                "shared_5",
                "rolling_only_1",
                "rolling_only_2",
                "rolling_only_3",
                "rolling_only_4",
                "rolling_only_5");

        TestPlanFeedbackPacket oldOld = lanePacket(
                testPacketId,
                baseline,
                singleWindow("POST_STAGE_1",
                        TraceWindow.StageKind.POST_STAGE,
                        setOf(0),
                        Collections.<Integer> emptySet(),
                        "old-old",
                        baseline));
        TestPlanFeedbackPacket rollingPacket = lanePacket(
                testPacketId,
                rolling,
                singleWindow("POST_STAGE_1",
                        TraceWindow.StageKind.POST_STAGE,
                        setOf(0),
                        setOf(0),
                        "old-new",
                        rolling));
        TestPlanFeedbackPacket newNew = lanePacket(
                testPacketId,
                baseline,
                singleWindow("POST_STAGE_1",
                        TraceWindow.StageKind.POST_STAGE,
                        setOf(0),
                        setOf(0, 1),
                        "new-new",
                        baseline));
        return new TestPlanDiffFeedbackPacket("cassandra", testPacketId,
                new TestPlanFeedbackPacket[] {
                        oldOld,
                        rollingPacket,
                        newNew
                });
    }

    private static TestPlanFeedbackPacket lanePacket(int testPacketId,
            Trace laneTrace,
            WindowedTrace windowedTrace) {
        TestPlanFeedbackPacket packet = new TestPlanFeedbackPacket(
                "cassandra",
                "test0",
                testPacketId,
                new FeedBack[] { new FeedBack() });
        packet.trace = new Trace[] { laneTrace.copy() };
        packet.windowedTrace = windowedTrace;
        packet.validationReadResults = new LinkedList<>();
        packet.validationResults = null;
        return packet;
    }

    private static WindowedTrace singleWindow(String stageId,
            TraceWindow.StageKind stageKind,
            Set<Integer> transitionNodes,
            Set<Integer> rawUpgradedNodes,
            String layout,
            Trace trace) {
        WindowedTrace windowedTrace = new WindowedTrace();
        windowedTrace.addWindow(new TraceWindow(
                0,
                stageId + "_OPEN",
                stageId + "_CLOSE",
                0,
                stageId,
                stageKind,
                transitionNodes,
                rawUpgradedNodes,
                layout,
                true,
                new Trace[] { trace.copy() }));
        return windowedTrace;
    }

    private static Set<Integer> setOf(int... values) {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        for (int value : values) {
            set.add(value);
        }
        return set;
    }

    private static Trace traceOf(String... messages) {
        Trace trace = new Trace();
        int idx = 0;
        for (String message : messages) {
            trace.recordSend("TraceSignatureDedupTest.fakeSend", 10010001,
                    new int[] { idx }, message,
                    SendMeta.builder().messageType("ReplayMessage").build(),
                    message);
            idx++;
        }
        return trace;
    }

    private static Map<String, Integer> csvHeaderIndex(String headerLine) {
        String[] columns = headerLine.split(",", -1);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            index.put(columns[i], i);
        }
        return index;
    }
}
