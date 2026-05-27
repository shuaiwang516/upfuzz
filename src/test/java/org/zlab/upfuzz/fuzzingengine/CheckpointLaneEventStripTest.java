package org.zlab.upfuzz.fuzzingengine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanPacket;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;
import org.zlab.upfuzz.fuzzingengine.testplan.event.fault.RestartFailure;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.FinalizeUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.HDFSStopSNN;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.PrepareUpgrade;
import org.zlab.upfuzz.fuzzingengine.testplan.event.upgradeop.UpgradeOp;

class CheckpointLaneEventStripTest {

    @BeforeEach
    void initConfig() {
        if (Config.getConf() == null) {
            new Config();
        }
        Config.getConf().nodeNum = 3;
        Config.getConf().checkpointSelectedNodes = new int[] { 0 };
    }

    @Test
    void rollingLaneRemovesPreparedPrefixOnly() {
        List<Event> events = new ArrayList<>();
        events.add(new PrepareUpgrade());
        events.add(new ShellCommand("cmd-before", 0));
        events.add(new UpgradeOp(0));
        events.add(new HDFSStopSNN());
        events.add(new UpgradeOp(1));
        events.add(new FinalizeUpgrade());
        events.add(new ShellCommand("cmd-after", 1));

        TestPlanPacket packet = packet(events);

        TestPlanPacket result = FuzzingClient
                .prepareCheckpointLaneTestPlan(packet, true);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(4, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof ShellCommand);
        assertTrue(resultEvents.get(1) instanceof UpgradeOp);
        assertEquals(1, ((UpgradeOp) resultEvents.get(1)).nodeIndex);
        assertTrue(resultEvents.get(2) instanceof FinalizeUpgrade);
        assertTrue(resultEvents.get(3) instanceof ShellCommand);
    }

    @Test
    void baselineLaneRemovesSelectedRestartPrefixOnly() {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("cmd-before", 0));
        events.add(new RestartFailure(0));
        events.add(new RestartFailure(1));
        events.add(new ShellCommand("cmd-after", 1));

        TestPlanPacket packet = packet(events);

        TestPlanPacket result = FuzzingClient
                .prepareCheckpointLaneTestPlan(packet, false);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(3, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof ShellCommand);
        assertTrue(resultEvents.get(1) instanceof RestartFailure);
        assertEquals(1, ((RestartFailure) resultEvents.get(1)).nodeIndex);
        assertTrue(resultEvents.get(2) instanceof ShellCommand);
    }

    @Test
    void fastCheckpointSuffixRemovesAllRestartUpgradeEvents() {
        List<Event> events = new ArrayList<>();
        events.add(new PrepareUpgrade());
        events.add(new ShellCommand("cmd-before", 0));
        events.add(new RestartFailure(0));
        events.add(new UpgradeOp(1));
        events.add(new HDFSStopSNN());
        events.add(new FinalizeUpgrade());
        events.add(new ShellCommand("cmd-after", 1));

        TestPlanPacket packet = packet(events);

        TestPlanPacket result = FuzzingClient
                .stripPostCheckpointLifecycleEvents(packet,
                        FuzzingClient.LANE_ROLLING);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(2, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof ShellCommand);
        assertTrue(resultEvents.get(1) instanceof ShellCommand);
    }

    @Test
    void fastCheckpointRollingLaneLeavesOnlyWorkloadSuffix() {
        List<Event> events = new ArrayList<>();
        events.add(new PrepareUpgrade());
        events.add(new ShellCommand("cmd-before", 0));
        events.add(new UpgradeOp(0));
        events.add(new HDFSStopSNN());
        events.add(new UpgradeOp(1));
        events.add(new FinalizeUpgrade());
        events.add(new ShellCommand("cmd-after", 1));

        TestPlanPacket packet = packet(events);

        TestPlanPacket result = FuzzingClient.prepareFastCheckpointLaneTestPlan(
                packet, true, FuzzingClient.LANE_ROLLING);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(2, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof ShellCommand);
        assertTrue(resultEvents.get(1) instanceof ShellCommand);
    }

    @Test
    void fastCheckpointBaselineLaneLeavesOnlyWorkloadSuffix() {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("cmd-before", 0));
        events.add(new RestartFailure(0));
        events.add(new RestartFailure(1));
        events.add(new ShellCommand("cmd-after", 1));

        TestPlanPacket packet = packet(events);

        TestPlanPacket result = FuzzingClient.prepareFastCheckpointLaneTestPlan(
                packet, false, FuzzingClient.LANE_ONLY_OLD);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(2, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof ShellCommand);
        assertTrue(resultEvents.get(1) instanceof ShellCommand);
    }

    @Test
    void fastCheckpointSuffixIsDisabledWhenCheckpointRestoreIsOff() {
        Config.getConf().enableCheckpointRestore = false;
        Config.getConf().checkpointAllLanes = true;

        assertFalse(FuzzingClient
                .isFastCheckpointSuffixLane(FuzzingClient.LANE_ROLLING));
        assertFalse(FuzzingClient
                .isFastCheckpointSuffixLane(FuzzingClient.LANE_ONLY_OLD));

        Config.getConf().enableCheckpointRestore = true;
        assertTrue(FuzzingClient
                .isFastCheckpointSuffixLane(FuzzingClient.LANE_ROLLING));
        assertTrue(FuzzingClient
                .isFastCheckpointSuffixLane(FuzzingClient.LANE_ONLY_OLD));

        Config.getConf().checkpointAllLanes = false;
        assertTrue(FuzzingClient
                .isFastCheckpointSuffixLane(FuzzingClient.LANE_ROLLING));
        assertFalse(FuzzingClient
                .isFastCheckpointSuffixLane(FuzzingClient.LANE_ONLY_OLD));
    }

    @Test
    void invalidSelectedNodeFailsFast() {
        Config.getConf().checkpointSelectedNodes = new int[] { 3 };
        List<Event> events = new ArrayList<>();
        events.add(new UpgradeOp(0));
        TestPlanPacket packet = packet(events);

        assertThrows(IllegalArgumentException.class,
                () -> FuzzingClient.prepareCheckpointLaneTestPlan(packet,
                        true));
    }

    private static TestPlanPacket packet(List<Event> events) {
        TestPlan testPlan = new TestPlan(3, events,
                Arrays.asList("validate"), new LinkedList<>());
        return new TestPlanPacket("cassandra", 1, "config", testPlan);
    }
}
