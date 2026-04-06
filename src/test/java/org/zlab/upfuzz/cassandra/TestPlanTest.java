package org.zlab.upfuzz.cassandra;

import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.AbstractTest;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.server.FullStopSeed;
import org.zlab.upfuzz.fuzzingengine.server.FuzzingServer;
import org.zlab.upfuzz.fuzzingengine.server.Seed;
import org.zlab.upfuzz.fuzzingengine.testplan.TestPlan;
import org.zlab.upfuzz.fuzzingengine.testplan.event.Event;
import org.zlab.upfuzz.fuzzingengine.testplan.event.command.ShellCommand;

import java.util.LinkedList;
import java.util.List;

public class TestPlanTest extends AbstractTest {

    @Test
    public void testPlan() {
        CassandraCommandPool cassandraCommandPool = new CassandraCommandPool();
        Config.getConf().system = "cassandra";
        Seed seed = generateSeed(cassandraCommandPool, CassandraState.class,
                -1);

        List<Event> shellCommands = ShellCommand.seedWriteCmd2Events(seed, 3);

        for (Event event : shellCommands) {
            if (event instanceof ShellCommand) {
                ShellCommand shellCommand = (ShellCommand) event;
                System.out.println("Node Index: " + shellCommand.getCommand());
                System.out.println("Command: " + shellCommand.getNodeIndex());
                System.out.println("Interval: " + shellCommand.interval);
            }
        }
    }

    @Test
    public void testTestPlanMutation() {
        CassandraCommandPool cassandraCommandPool = new CassandraCommandPool();
        Config.getConf().system = "cassandra";
        Seed seed = generateSeed(cassandraCommandPool, CassandraState.class,
                -1);

        FullStopSeed fullStopSeed = new FullStopSeed(seed, new LinkedList<>());

        TestPlan testPlan = null;
        for (int i = 0; i < 20; i++) {
            testPlan = FuzzingServer.generateTestPlan(fullStopSeed);
            if (testPlan != null)
                break;
        }

        if (testPlan == null) {
            System.out.println("Test plan is null");
            return;
        }

        testPlan.print();

        System.out.println();

        int repeatNum = 1;

        for (int i = 0; i < repeatNum; i++) {

            TestPlan mutateTestPlan = SerializationUtils.clone(testPlan);

            if (!mutateTestPlan.mutate(cassandraCommandPool,
                    CassandraState.class)) {
                System.out.println("Testplan mutation failed");
                continue;
            }

            for (Event event : mutateTestPlan.getEvents())
                assert event != null;

            System.out.println("Testplan mutated successfully");
            mutateTestPlan.print();
        }
    }

    /**
     * Stress test: repeatedly mutate the same TestPlan (simulating corpus
     * re-use). Without the type-4 seed sync fix, this triggers
     * AssertionError when shellCommandIdxes.size() !=
     * seed.originalCommandSequence.getSize() after consecutive type-4
     * mutations.
     */
    @Test
    public void testRepeatedMutationSeedSync() {
        CassandraCommandPool cassandraCommandPool = new CassandraCommandPool();
        Config.getConf().system = "cassandra";
        Seed seed = generateSeed(cassandraCommandPool, CassandraState.class,
                -1);

        FullStopSeed fullStopSeed = new FullStopSeed(seed, new LinkedList<>());

        TestPlan testPlan = null;
        for (int i = 0; i < 20; i++) {
            testPlan = FuzzingServer.generateTestPlan(fullStopSeed);
            if (testPlan != null)
                break;
        }

        assert testPlan != null : "Failed to generate initial test plan";

        int successCount = 0;
        int failCount = 0;

        // Simulate corpus re-use: mutate, clone result, mutate again
        for (int round = 0; round < 200; round++) {
            TestPlan mutateTestPlan = SerializationUtils.clone(testPlan);
            boolean success = mutateTestPlan.mutate(cassandraCommandPool,
                    CassandraState.class);

            if (!success) {
                failCount++;
                continue;
            }

            // Verify events and seed stay in sync
            if (mutateTestPlan.seed != null
                    && mutateTestPlan.seed.originalCommandSequence != null) {
                List<Integer> shellIdxes = mutateTestPlan
                        .getIdxes(mutateTestPlan.getEvents(),
                                ShellCommand.class);
                int seedSize = mutateTestPlan.seed.originalCommandSequence
                        .getSize();
                assert shellIdxes.size() == seedSize
                        : "Desync at round " + round
                                + ": shellCommandIdxes=" + shellIdxes.size()
                                + " seed=" + seedSize;

                // Verify validationCommands are refreshed
                assert mutateTestPlan.validationCommands != null
                        : "validationCommands null at round " + round;
            }

            // Use mutated plan as the base for next round (corpus re-use)
            testPlan = mutateTestPlan;
            successCount++;
        }

        System.out.println("Repeated mutation test: " + successCount
                + " successes, " + failCount + " failures out of 200 rounds");
        assert successCount > 0 : "No mutations succeeded";
    }
}
