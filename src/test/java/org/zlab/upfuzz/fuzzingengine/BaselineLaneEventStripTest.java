package org.zlab.upfuzz.fuzzingengine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeAll;
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

/**
 * Verifies that replaceUpgradeEventWithRestart strips all upgrade lifecycle
 * events (PrepareUpgrade, FinalizeUpgrade, HDFSStopSNN) and converts
 * UpgradeOp to RestartFailure for baseline lanes.
 */
class BaselineLaneEventStripTest {

    @BeforeAll
    static void initConfig() {
        if (Config.getConf() == null) {
            new Config();
        }
    }

    @Test
    void upgradeOpReplacedWithRestart() {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("CREATE TABLE t", 0));
        events.add(new UpgradeOp(0));
        events.add(new ShellCommand("INSERT INTO t", 0));

        TestPlan testPlan = new TestPlan(2, events,
                Arrays.asList("SELECT * FROM t"), new LinkedList<>());
        TestPlanPacket packet = new TestPlanPacket("cassandra", 1, "config",
                testPlan);

        TestPlanPacket result = FuzzingClient
                .replaceUpgradeEventWithRestart(packet);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(3, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof ShellCommand);
        assertTrue(resultEvents.get(1) instanceof RestartFailure);
        assertTrue(resultEvents.get(2) instanceof ShellCommand);
    }

    @Test
    void prepareAndFinalizeUpgradeStripped() {
        List<Event> events = new ArrayList<>();
        events.add(new PrepareUpgrade());
        events.add(new UpgradeOp(0));
        events.add(new FinalizeUpgrade());

        TestPlan testPlan = new TestPlan(2, events,
                Arrays.asList("cmd"), new LinkedList<>());
        TestPlanPacket packet = new TestPlanPacket("cassandra", 1, "config",
                testPlan);

        TestPlanPacket result = FuzzingClient
                .replaceUpgradeEventWithRestart(packet);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(1, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof RestartFailure);
    }

    @Test
    void hdfsStopSNNStripped() {
        List<Event> events = new ArrayList<>();
        events.add(new HDFSStopSNN());
        events.add(new UpgradeOp(0));
        events.add(new UpgradeOp(1));
        events.add(new FinalizeUpgrade());

        TestPlan testPlan = new TestPlan(2, events,
                Arrays.asList("cmd"), new LinkedList<>());
        TestPlanPacket packet = new TestPlanPacket("hdfs", 1, "config",
                testPlan);

        TestPlanPacket result = FuzzingClient
                .replaceUpgradeEventWithRestart(packet);
        List<Event> resultEvents = result.getTestPlan().events;

        assertEquals(2, resultEvents.size());
        assertTrue(resultEvents.get(0) instanceof RestartFailure);
        assertTrue(resultEvents.get(1) instanceof RestartFailure);
    }

    @Test
    void noUpgradeLifecycleEventsInResult() {
        List<Event> events = new ArrayList<>();
        events.add(new ShellCommand("cmd1", 0));
        events.add(new PrepareUpgrade());
        events.add(new UpgradeOp(0));
        events.add(new ShellCommand("cmd2", 1));
        events.add(new UpgradeOp(1));
        events.add(new FinalizeUpgrade());
        events.add(new ShellCommand("cmd3", 0));

        TestPlan testPlan = new TestPlan(2, events,
                Arrays.asList("v"), new LinkedList<>());
        TestPlanPacket packet = new TestPlanPacket("cassandra", 1, "config",
                testPlan);

        TestPlanPacket result = FuzzingClient
                .replaceUpgradeEventWithRestart(packet);
        List<Event> resultEvents = result.getTestPlan().events;

        for (Event e : resultEvents) {
            assertFalse(e instanceof PrepareUpgrade,
                    "PrepareUpgrade should not appear in baseline lane");
            assertFalse(e instanceof FinalizeUpgrade,
                    "FinalizeUpgrade should not appear in baseline lane");
            assertFalse(e instanceof HDFSStopSNN,
                    "HDFSStopSNN should not appear in baseline lane");
            assertFalse(e instanceof UpgradeOp,
                    "UpgradeOp should not appear in baseline lane");
        }

        // 3 ShellCommands + 2 RestartFailures = 5
        assertEquals(5, resultEvents.size());
    }
}
