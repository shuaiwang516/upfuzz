package org.zlab.upfuzz.fuzzingengine.executor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.zlab.net.tracker.Trace;
import org.zlab.upfuzz.docker.*;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.LogInfo;
import org.zlab.upfuzz.fuzzingengine.packet.ValidationResult;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.*;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.*;
import org.zlab.upfuzz.fuzzingengine.trace.TraceWindow;
import org.zlab.upfuzz.fuzzingengine.trace.WindowedTrace;
import org.zlab.upfuzz.fuzzingengine.trace.DisruptiveEventCategory;
import org.zlab.upfuzz.fuzzingengine.testplan.event.downgradeop.DowngradeOp;
import org.zlab.ocov.tracker.ObjectGraphCoverage;

/**
 * Comprehensive verification of Phase 1: Windowed Trace Collection.
 * Covers checklist items 1-14 from the Phase 1 plan.
 */
class WindowedTraceCollectionTest {

    private static final int NODE_NUM = 3;

    /** Tracks all clearTrace / collectTrace calls for assertion. */
    static class StubDocker extends DockerMeta implements IDocker {
        int clearTraceCount = 0;
        int collectTraceCount = 0;

        StubDocker(int index) {
            this.index = index;
            this.networkIP = "10.0.0." + (index + 1);
        }

        @Override
        public String getNetworkIP() {
            return networkIP;
        }

        @Override
        public int start() {
            return 0;
        }

        @Override
        public void teardown() {
        }

        @Override
        public boolean build() {
            return true;
        }

        @Override
        public void flush() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void upgrade() {
        }

        @Override
        public void upgradeFromCrash() {
        }

        @Override
        public void downgrade() {
        }

        @Override
        public ObjectGraphCoverage getFormatCoverage() {
            return null;
        }

        @Override
        public Trace collectTrace() {
            collectTraceCount++;
            return new Trace();
        }

        @Override
        public void clearTrace() {
            clearTraceCount++;
        }

        @Override
        public void clearFormatCoverage() {
        }

        @Override
        public boolean clear() {
            return true;
        }

        @Override
        public LogInfo grepLogInfo(Set<String> blackListErrorLog) {
            return null;
        }

        @Override
        public String formatComposeYaml() {
            return "";
        }

        @Override
        public String execCommand(String command) {
            return "OK";
        }
    }

    /** Minimal concrete DockerCluster that stubs all abstract methods. */
    static class StubDockerCluster extends DockerCluster {
        int clearAllCount = 0;
        int collectAllCount = 0;

        StubDockerCluster(Executor executor, int nodeNum) {
            super(executor, "test-version", nodeNum, false, 0);
            this.dockerStates = new DockerMeta.DockerState[nodeNum];
            for (int i = 0; i < nodeNum; i++) {
                dockerStates[i] = new DockerMeta.DockerState(
                        DockerMeta.DockerVersion.original, true);
            }
        }

        @Override
        public void clearTraceAllNodes() {
            clearAllCount++;
        }

        @Override
        public Trace[] collectTraceAllNodes() {
            collectAllCount++;
            Trace[] traces = new Trace[nodeNum];
            for (int i = 0; i < nodeNum; i++) {
                traces[i] = new Trace();
            }
            return traces;
        }

        // --- Abstract method stubs ---
        @Override
        public boolean restartContainer(int nodeIndex) {
            return true;
        }

        @Override
        public boolean killContainerRecover(int nodeIndex) {
            return true;
        }

        @Override
        public boolean stopContainer(int nodeIndex) {
            return true;
        }

        @Override
        public void upgrade(int nodeIndex) {
        }

        @Override
        public void downgrade(int nodeIndex) {
        }

        @Override
        public void teardown() {
        }

        @Override
        public void formatComposeYaml() {
        }

        @Override
        public void refreshNetwork() {
        }

        @Override
        public void prepareUpgrade() {
        }

        @Override
        public void finalizeUpgrade() {
        }

        @Override
        public String getNetworkIP() {
            return "10.0.0.0";
        }

        @Override
        public int start() {
            return 0;
        }

        @Override
        public boolean build() {
            return true;
        }

        @Override
        public boolean fullStopUpgrade() {
            return true;
        }

        @Override
        public boolean rollingUpgrade() {
            return true;
        }

        @Override
        public boolean downgrade() {
            return true;
        }

        @Override
        public void flush() {
        }

        @Override
        public boolean freshStartNewVersion() {
            return true;
        }

        @Override
        public IDocker getDocker(int i) {
            return new StubDocker(i);
        }
    }

    /** Minimal concrete Executor for testing. */
    static class TestableExecutor extends Executor {
        TestableExecutor(int nodeNum) {
            this(nodeNum, 0);
        }

        TestableExecutor(int nodeNum, int direction) {
            super("test-system", nodeNum);
            this.direction = direction;
            this.dockerCluster = new StubDockerCluster(this, nodeNum);
            // Initialize agent infrastructure to avoid NPE
            this.agentStore = new HashMap<>();
            this.agentHandler = new HashMap<>();
            this.sessionGroup = new java.util.concurrent.ConcurrentHashMap<>();
        }

        @Override
        public boolean startup() {
            return true;
        }

        StubDockerCluster getStubCluster() {
            return (StubDockerCluster) dockerCluster;
        }
    }

    @BeforeAll
    static void initConfig() {
        if (Config.getConf() == null) {
            new Config();
        }
        // Enable trace for all tests
        Config.getConf().useTrace = true;
        // Set minimal interval (must be >0 for randWithRange)
        Config.getConf().intervalMin = 1;
        Config.getConf().intervalMax = 2;
    }

    private TestableExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TestableExecutor(NODE_NUM);
    }

    private TestPlan makePlan(Event... events) {
        List<Event> list = new ArrayList<>(Arrays.asList(events));
        // Force zero interval on all events
        for (Event e : list) {
            e.interval = 0;
        }
        return new TestPlan(NODE_NUM, list,
                Collections.emptyList(), Collections.emptyList());
    }

    // =====================================================================
    // Checklist Item 1: Startup traces cleared
    // =====================================================================
    @Test
    void item1_startupTracesCleared() {
        TestPlan plan = makePlan(new ShellCommand("cmd1", 0));
        executor.execute(plan);

        // clearTraceAllNodes should be called at least once at startup
        assertTrue(executor.getStubCluster().clearAllCount >= 1,
                "clearTraceAllNodes must be called at startup");
    }

    // =====================================================================
    // Checklist Item 2: Window opens at first ShellCommand
    // =====================================================================
    @Test
    void item2_windowOpensAtFirstShellCommand() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertFalse(windows.isEmpty(), "At least one window should be created");
        assertEquals(0, windows.get(0).ordinal,
                "First window ordinal should be 0");
        assertEquals("PRE_UPGRADE_WORKLOAD", windows.get(0).openReason,
                "First window should open as PRE_UPGRADE_WORKLOAD");
    }

    // =====================================================================
    // Checklist Item 3: Window closes before UpgradeOp
    // =====================================================================
    @Test
    void item3_windowClosesBeforeUpgradeOp() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertTrue(windows.size() >= 2,
                "Should have at least 2 windows (before and after upgrade)");

        // First window closed by the UpgradeOp
        TraceWindow w0 = windows.get(0);
        assertTrue(w0.closeReason.contains("UpgradeOp"),
                "First window closeReason should mention UpgradeOp, got: "
                        + w0.closeReason);
        assertEquals(1, w0.closeEventIdx,
                "closeEventIdx should point to the UpgradeOp event index");
    }

    // =====================================================================
    // Checklist Item 4: Traces cleared after UpgradeOp
    // =====================================================================
    @Test
    void item4_tracesClearedAfterUpgradeOp() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        // clearTraceAllNodes: 1 at startup + 1 after UpgradeOp = at least 2
        assertTrue(executor.getStubCluster().clearAllCount >= 2,
                "clearTraceAllNodes should be called at least twice "
                        + "(startup + after UpgradeOp), got: "
                        + executor.getStubCluster().clearAllCount);
    }

    // =====================================================================
    // Checklist Item 5: Window reopens at next ShellCommand after upgrade
    // =====================================================================
    @Test
    void item5_windowReopensAtNextShellCommandAfterUpgrade() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertTrue(windows.size() >= 2,
                "Should have at least 2 windows");

        TraceWindow w1 = windows.get(1);
        assertEquals("POST_STAGE_1_WORKLOAD", w1.openReason,
                "Second window should open as POST_STAGE_1_WORKLOAD");
        assertEquals("ROUND_END", w1.closeReason,
                "Second window should close at ROUND_END");
    }

    // =====================================================================
    // Checklist Item 6: Stage IDs advance correctly
    // =====================================================================
    @Test
    void item6_stageIdsAdvanceCorrectly() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0),
                new UpgradeOp(1),
                new ShellCommand("cmd3", 0),
                new UpgradeOp(2),
                new ShellCommand("cmd4", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(4, windows.size(),
                "Should have 4 windows: PRE + 3 POST_STAGE");

        assertEquals("PRE_UPGRADE", windows.get(0).comparisonStageId);
        assertEquals("POST_STAGE_1", windows.get(1).comparisonStageId);
        assertEquals("POST_STAGE_2", windows.get(2).comparisonStageId);
        assertEquals("POST_FINAL_STAGE", windows.get(3).comparisonStageId);

        assertEquals(TraceWindow.StageKind.PRE_UPGRADE,
                windows.get(0).stageKind);
        assertEquals(TraceWindow.StageKind.POST_STAGE,
                windows.get(1).stageKind);
        assertEquals(TraceWindow.StageKind.POST_STAGE,
                windows.get(2).stageKind);
        assertEquals(TraceWindow.StageKind.POST_FINAL_STAGE,
                windows.get(3).stageKind);
    }

    // =====================================================================
    // Checklist Item 7: normalizedTransitionNodeSet accumulates
    // =====================================================================
    @Test
    void item7_normalizedTransitionNodeSetAccumulates() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0),
                new UpgradeOp(1),
                new ShellCommand("cmd3", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(3, windows.size());

        // PRE_UPGRADE window: no transitions yet
        assertEquals(Collections.emptySet(),
                windows.get(0).normalizedTransitionNodeSet);

        // After UpgradeOp(0): {0}
        assertEquals(new HashSet<>(Arrays.asList(0)),
                windows.get(1).normalizedTransitionNodeSet);

        // After UpgradeOp(1): {0, 1}
        assertEquals(new HashSet<>(Arrays.asList(0, 1)),
                windows.get(2).normalizedTransitionNodeSet);
    }

    // =====================================================================
    // Checklist Item 8: rawUpgradedNodeSet correct per lane
    // =====================================================================
    @Test
    void item8_rawUpgradedNodeSet_rollingLane() {
        // Simulates rolling lane: UpgradeOps advance rawUpgradedNodeSet
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0),
                new UpgradeOp(1),
                new ShellCommand("cmd3", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(3, windows.size());

        // PRE_UPGRADE: no upgrades yet
        assertEquals(Collections.emptySet(),
                windows.get(0).rawUpgradedNodeSet);
        // After UpgradeOp(0): {0}
        assertEquals(new HashSet<>(Arrays.asList(0)),
                windows.get(1).rawUpgradedNodeSet);
        // After UpgradeOp(1): {0, 1}
        assertEquals(new HashSet<>(Arrays.asList(0, 1)),
                windows.get(2).rawUpgradedNodeSet);
    }

    @Test
    void item8_rawUpgradedNodeSet_baselineLane() {
        // Simulates baseline lane: RestartFailure instead of UpgradeOp
        // rawUpgradedNodeSet should stay empty (no actual upgrade)
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new RestartFailure(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(2, windows.size());

        // RestartFailure advances normalizedTransitionNodeSet but NOT
        // rawUpgradedNodeSet
        assertEquals(Collections.emptySet(),
                windows.get(0).rawUpgradedNodeSet);
        assertEquals(Collections.emptySet(),
                windows.get(1).rawUpgradedNodeSet);

        // But normalizedTransitionNodeSet DOES advance
        assertEquals(new HashSet<>(Arrays.asList(0)),
                windows.get(1).normalizedTransitionNodeSet);
    }

    // =====================================================================
    // Checklist Item 9: rawVersionLayout correct
    // =====================================================================
    @Test
    void item9_rawVersionLayout() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0),
                new UpgradeOp(1),
                new ShellCommand("cmd3", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(3, windows.size());

        assertEquals("old-old-old", windows.get(0).rawVersionLayout);
        assertEquals("new-old-old", windows.get(1).rawVersionLayout);
        assertEquals("new-new-old", windows.get(2).rawVersionLayout);
    }

    @Test
    void item9_rawVersionLayout_baseline() {
        // Baseline: RestartFailure doesn't change version layout
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new RestartFailure(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals("old-old-old", windows.get(0).rawVersionLayout);
        assertEquals("old-old-old", windows.get(1).rawVersionLayout);
    }

    // =====================================================================
    // Checklist Item 10: LIFECYCLE_ONLY windows not comparable
    // =====================================================================
    @Test
    void item10_lifecycleOnlyNotComparable() {
        // Event sequence:
        // ShellCommand -> Window 0 opens (PRE_UPGRADE, comparable)
        // PrepareUpgrade -> Window 0 closes; stageKind set to LIFECYCLE_ONLY
        // ShellCommand -> Window 1 opens (LIFECYCLE_ONLY, NOT comparable)
        // UpgradeOp -> Window 1 closes; stage advances to POST_STAGE_1
        // ShellCommand -> Window 2 opens (POST_STAGE, comparable)
        // FinalizeUpgrade -> Window 2 closes; stageKind set to LIFECYCLE_ONLY
        // ShellCommand -> Window 3 opens (LIFECYCLE_ONLY, NOT comparable)
        // ROUND_END -> Window 3 closes
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new PrepareUpgrade(),
                new ShellCommand("cmd2", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd3", 0),
                new FinalizeUpgrade(),
                new ShellCommand("cmd4", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(4, windows.size());

        // Window 0: PRE_UPGRADE, closed by PrepareUpgrade -> comparable
        // (stageKind was PRE_UPGRADE when window was created)
        assertTrue(windows.get(0).comparableAcrossLanes,
                "Window 0 (PRE_UPGRADE) should be comparable");
        assertEquals(TraceWindow.StageKind.PRE_UPGRADE,
                windows.get(0).stageKind);

        // Window 1: opened after PrepareUpgrade set stageKind=LIFECYCLE_ONLY
        assertFalse(windows.get(1).comparableAcrossLanes,
                "Window 1 (between PrepareUpgrade and UpgradeOp) "
                        + "should NOT be comparable");
        assertEquals(TraceWindow.StageKind.LIFECYCLE_ONLY,
                windows.get(1).stageKind);

        // Window 2: opened after UpgradeOp reset stageKind to POST_STAGE
        assertTrue(windows.get(2).comparableAcrossLanes,
                "Window 2 (POST_STAGE) should be comparable");
        assertEquals(TraceWindow.StageKind.POST_STAGE,
                windows.get(2).stageKind);

        // Window 3: opened after FinalizeUpgrade set stageKind=LIFECYCLE_ONLY
        assertFalse(windows.get(3).comparableAcrossLanes,
                "Window 3 (after FinalizeUpgrade) should NOT be comparable");
        assertEquals(TraceWindow.StageKind.LIFECYCLE_ONLY,
                windows.get(3).stageKind);
    }

    // =====================================================================
    // Checklist Item 11: WindowedTrace serializes correctly
    // =====================================================================
    @Test
    void item11_windowedTraceSerializes() throws Exception {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        WindowedTrace wt = new WindowedTrace();
        for (TraceWindow w : executor.getTraceWindows()) {
            wt.addWindow(w);
        }

        // Serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(wt);
        oos.close();

        // Deserialize
        ByteArrayInputStream bais = new ByteArrayInputStream(
                baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        WindowedTrace deserialized = (WindowedTrace) ois.readObject();
        ois.close();

        assertEquals(wt.size(), deserialized.size(),
                "Deserialized WindowedTrace should have same window count");
        for (int i = 0; i < wt.size(); i++) {
            TraceWindow orig = wt.getWindows().get(i);
            TraceWindow deser = deserialized.getWindows().get(i);
            assertEquals(orig.ordinal, deser.ordinal);
            assertEquals(orig.comparisonStageId, deser.comparisonStageId);
            assertEquals(orig.stageKind, deser.stageKind);
            assertEquals(orig.normalizedTransitionNodeSet,
                    deser.normalizedTransitionNodeSet);
            assertEquals(orig.rawUpgradedNodeSet, deser.rawUpgradedNodeSet);
            assertEquals(orig.rawVersionLayout, deser.rawVersionLayout);
            assertEquals(orig.comparableAcrossLanes,
                    deser.comparableAcrossLanes);
            assertEquals(orig.openReason, deser.openReason);
            assertEquals(orig.closeReason, deser.closeReason);
        }
    }

    // =====================================================================
    // Checklist Item 12: Legacy flat trace still populated
    // =====================================================================
    @Test
    void item12_legacyFlatTraceStillPopulated() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        // Legacy trace array should still be present and non-null
        assertNotNull(executor.trace, "Legacy trace array should not be null");
        assertEquals(NODE_NUM, executor.trace.length,
                "Legacy trace array should have one entry per node");
        for (int i = 0; i < NODE_NUM; i++) {
            assertNotNull(executor.trace[i],
                    "Legacy trace[" + i + "] should not be null");
        }
    }

    // =====================================================================
    // Checklist Item 13: Multiple UpgradeOps produce multiple windows
    // =====================================================================
    @Test
    void item13_multipleUpgradeOpsProduceMultipleWindows() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0),
                new UpgradeOp(1),
                new ShellCommand("cmd3", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(3, windows.size(),
                "2 upgrades should produce 3 workload windows");
    }

    // =====================================================================
    // Checklist Item 14: Back-to-back disruptive events: no empty window
    // =====================================================================
    @Test
    void item14_backToBackDisruptiveEventsNoEmptyWindow() {
        // Two UpgradeOps with no ShellCommand between them
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new UpgradeOp(1),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(2, windows.size(),
                "Back-to-back upgrades should NOT produce an empty window between them");

        // First window: PRE_UPGRADE, closed by first UpgradeOp
        assertEquals("PRE_UPGRADE", windows.get(0).comparisonStageId);
        // Second window: POST_STAGE_2 (both upgrades happened), opened at cmd2
        assertEquals("POST_STAGE_2", windows.get(1).comparisonStageId);
    }

    // =====================================================================
    // Additional edge case tests
    // =====================================================================

    @Test
    void edgeCase_emptyPlan() {
        TestPlan plan = makePlan(); // no events
        boolean status = executor.execute(plan);
        assertTrue(status);
        assertTrue(executor.getTraceWindows().isEmpty(),
                "Empty plan should produce no windows");
    }

    @Test
    void edgeCase_onlyWorkloadEvents() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new ShellCommand("cmd2", 0),
                new ShellCommand("cmd3", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(1, windows.size(),
                "Only workload events should produce one window");
        assertEquals("PRE_UPGRADE", windows.get(0).comparisonStageId);
        assertEquals("ROUND_END", windows.get(0).closeReason);
    }

    @Test
    void edgeCase_startsWithDisruptiveEvent() {
        // Round starts with UpgradeOp (no workload before)
        TestPlan plan = makePlan(
                new UpgradeOp(0),
                new ShellCommand("cmd1", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(1, windows.size(),
                "Should have one window after the UpgradeOp");
        assertEquals("POST_STAGE_1", windows.get(0).comparisonStageId);
    }

    @Test
    void edgeCase_endsWithDisruptiveEvent() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(1, windows.size(),
                "Should have one window closed by UpgradeOp, no final window");
        assertEquals("PRE_UPGRADE", windows.get(0).comparisonStageId);
    }

    @Test
    void disruptiveEventCategory_classification() {
        assertTrue(DisruptiveEventCategory.classify(
                new UpgradeOp(0)).advancesComparisonStage());
        assertTrue(DisruptiveEventCategory.classify(
                new RestartFailure(0)).advancesComparisonStage());
        assertTrue(DisruptiveEventCategory.classify(
                new DowngradeOp(0)).advancesComparisonStage());

        assertEquals(DisruptiveEventCategory.LIFECYCLE_ONLY,
                DisruptiveEventCategory.classify(new PrepareUpgrade()));
        assertEquals(DisruptiveEventCategory.LIFECYCLE_ONLY,
                DisruptiveEventCategory.classify(new FinalizeUpgrade()));
        assertEquals(DisruptiveEventCategory.LIFECYCLE_ONLY,
                DisruptiveEventCategory.classify(new HDFSStopSNN()));

        assertEquals(DisruptiveEventCategory.FAULT,
                DisruptiveEventCategory.classify(new NodeFailure(0)));

        assertEquals(DisruptiveEventCategory.WORKLOAD,
                DisruptiveEventCategory.classify(new ShellCommand("x", 0)));

        assertFalse(DisruptiveEventCategory.classify(
                new ShellCommand("x", 0)).isDisruptive());
        assertTrue(DisruptiveEventCategory.classify(
                new UpgradeOp(0)).isDisruptive());
    }

    @Test
    void fullSequence_threeNodeRolling() {
        // Full 3-node rolling upgrade sequence
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new ShellCommand("cmd2", 1),
                new UpgradeOp(0),
                new ShellCommand("cmd3", 0),
                new ShellCommand("cmd4", 1),
                new UpgradeOp(1),
                new ShellCommand("cmd5", 0),
                new UpgradeOp(2),
                new ShellCommand("cmd6", 0),
                new ShellCommand("cmd7", 2));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(4, windows.size());

        // Window 0: PRE_UPGRADE
        assertEquals("PRE_UPGRADE", windows.get(0).comparisonStageId);
        assertEquals("old-old-old", windows.get(0).rawVersionLayout);
        assertEquals(Collections.emptySet(),
                windows.get(0).normalizedTransitionNodeSet);
        assertTrue(windows.get(0).comparableAcrossLanes);

        // Window 1: POST_STAGE_1 (node 0 upgraded)
        assertEquals("POST_STAGE_1", windows.get(1).comparisonStageId);
        assertEquals("new-old-old", windows.get(1).rawVersionLayout);
        assertEquals(new HashSet<>(Arrays.asList(0)),
                windows.get(1).normalizedTransitionNodeSet);
        assertEquals(new HashSet<>(Arrays.asList(0)),
                windows.get(1).rawUpgradedNodeSet);
        assertTrue(windows.get(1).comparableAcrossLanes);

        // Window 2: POST_STAGE_2 (nodes 0,1 upgraded)
        assertEquals("POST_STAGE_2", windows.get(2).comparisonStageId);
        assertEquals("new-new-old", windows.get(2).rawVersionLayout);
        assertEquals(new HashSet<>(Arrays.asList(0, 1)),
                windows.get(2).normalizedTransitionNodeSet);
        assertTrue(windows.get(2).comparableAcrossLanes);

        // Window 3: POST_FINAL_STAGE (all nodes upgraded)
        assertEquals("POST_FINAL_STAGE", windows.get(3).comparisonStageId);
        assertEquals("new-new-new", windows.get(3).rawVersionLayout);
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)),
                windows.get(3).normalizedTransitionNodeSet);
        assertEquals(TraceWindow.StageKind.POST_FINAL_STAGE,
                windows.get(3).stageKind);
        assertEquals("ROUND_END", windows.get(3).closeReason);
        assertTrue(windows.get(3).comparableAcrossLanes);
    }

    @Test
    void nodeTracesArrayHasCorrectLength() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        for (TraceWindow w : executor.getTraceWindows()) {
            assertEquals(NODE_NUM, w.nodeTraces.length,
                    "Each window should have NODE_NUM trace slots");
            for (int i = 0; i < NODE_NUM; i++) {
                assertNotNull(w.nodeTraces[i],
                        "nodeTraces[" + i + "] should not be null");
            }
        }
    }

    @Test
    void windowOrdinalsAreSequential() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0),
                new UpgradeOp(1),
                new ShellCommand("cmd3", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        for (int i = 0; i < windows.size(); i++) {
            assertEquals(i, windows.get(i).ordinal,
                    "Window ordinal should be sequential");
        }
    }

    // =====================================================================
    // New-new lane metadata (direction=1)
    // =====================================================================
    @Test
    void newNewLane_rawUpgradedNodeSetStartsFull() {
        // direction=1 means new-new lane: all nodes start upgraded
        TestableExecutor newNewExecutor = new TestableExecutor(NODE_NUM, 1);
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new RestartFailure(0),
                new ShellCommand("cmd2", 0));
        newNewExecutor.execute(plan);

        List<TraceWindow> windows = newNewExecutor.getTraceWindows();
        assertEquals(2, windows.size());

        // All nodes should be in rawUpgradedNodeSet from the start
        Set<Integer> allNodes = new HashSet<>(Arrays.asList(0, 1, 2));
        assertEquals(allNodes, windows.get(0).rawUpgradedNodeSet,
                "new-new lane should start with all nodes upgraded");
        assertEquals(allNodes, windows.get(1).rawUpgradedNodeSet);
    }

    @Test
    void newNewLane_rawVersionLayoutIsAllNew() {
        TestableExecutor newNewExecutor = new TestableExecutor(NODE_NUM, 1);
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new RestartFailure(0),
                new ShellCommand("cmd2", 0));
        newNewExecutor.execute(plan);

        List<TraceWindow> windows = newNewExecutor.getTraceWindows();
        assertEquals("new-new-new", windows.get(0).rawVersionLayout);
        assertEquals("new-new-new", windows.get(1).rawVersionLayout);
    }

    // =====================================================================
    // Legacy flat trace rebuild from windows
    // =====================================================================
    @Test
    void legacyTraceRebuiltFromWindows() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        // Rebuild legacy trace from windows
        executor.rebuildLegacyTraceFromWindows();

        assertNotNull(executor.trace);
        assertEquals(NODE_NUM, executor.trace.length);
        for (int i = 0; i < NODE_NUM; i++) {
            assertNotNull(executor.trace[i],
                    "Legacy trace[" + i + "] should not be null after rebuild");
        }
    }

    // =====================================================================
    // DowngradeOp handling
    // =====================================================================
    @Test
    void downgradeOpAdvancesStageWithCorrectNodeIndex() {
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new DowngradeOp(1),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(2, windows.size());

        // DowngradeOp(1) should advance stage and record node 1
        assertEquals("POST_STAGE_1", windows.get(1).comparisonStageId);
        assertEquals(new HashSet<>(Arrays.asList(1)),
                windows.get(1).normalizedTransitionNodeSet);
    }

    // =====================================================================
    // Fault/FaultRecover window tagging
    // =====================================================================
    @Test
    void faultWindowIsNonComparable() {
        // ShellCommand -> Window 0 (PRE_UPGRADE, comparable)
        // NodeFailure -> Window 0 closes; stageKind set to FAULT_RECOVERY
        // ShellCommand -> Window 1 (FAULT_RECOVERY, NOT comparable)
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new NodeFailure(1),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(2, windows.size());

        assertTrue(windows.get(0).comparableAcrossLanes,
                "Window 0 (PRE_UPGRADE) should be comparable");

        assertFalse(windows.get(1).comparableAcrossLanes,
                "Window 1 (after NodeFailure) should NOT be comparable");
        assertEquals(TraceWindow.StageKind.FAULT_RECOVERY,
                windows.get(1).stageKind);
    }

    @Test
    void faultRecoverWindowIsNonComparable() {
        // ShellCommand -> Window 0 (comparable)
        // NodeFailure -> Window 0 closes
        // NodeFailureRecover -> (no window open, just updates stageKind)
        // ShellCommand -> Window 1 (FAULT_RECOVERY, NOT comparable)
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new NodeFailure(1),
                new NodeFailureRecover(1),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(2, windows.size());

        assertTrue(windows.get(0).comparableAcrossLanes);

        assertFalse(windows.get(1).comparableAcrossLanes,
                "Window after fault+recover should NOT be comparable");
        assertEquals(TraceWindow.StageKind.FAULT_RECOVERY,
                windows.get(1).stageKind);
    }

    @Test
    void faultRecoveryResetByStageAdvancing() {
        // After fault, a stage-advancing event should reset stageKind
        TestPlan plan = makePlan(
                new ShellCommand("cmd1", 0),
                new NodeFailure(1),
                new NodeFailureRecover(1),
                new UpgradeOp(0),
                new ShellCommand("cmd2", 0));
        executor.execute(plan);

        List<TraceWindow> windows = executor.getTraceWindows();
        assertEquals(2, windows.size());

        // Window 0: PRE_UPGRADE (closed by NodeFailure)
        assertTrue(windows.get(0).comparableAcrossLanes);

        // Window 1: POST_STAGE (after UpgradeOp resets from FAULT_RECOVERY)
        assertTrue(windows.get(1).comparableAcrossLanes,
                "Window after fault+recover+UpgradeOp should be comparable");
        assertEquals(TraceWindow.StageKind.POST_STAGE,
                windows.get(1).stageKind);
    }
}
