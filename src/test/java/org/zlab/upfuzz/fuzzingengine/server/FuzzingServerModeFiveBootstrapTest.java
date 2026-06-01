package org.zlab.upfuzz.fuzzingengine.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.fuzzingengine.Config;
import org.zlab.upfuzz.fuzzingengine.configgen.ConfigGen;
import org.zlab.upfuzz.fuzzingengine.packet.Packet;
import org.zlab.upfuzz.fuzzingengine.packet.TestPlanPacket;
import org.zlab.upfuzz.hdfs.HdfsCommandPool;
import org.zlab.upfuzz.hdfs.HdfsState;

class FuzzingServerModeFiveBootstrapTest {

    @BeforeEach
    void resetConfig() {
        new Config();
    }

    @Test
    void guidedRollingBootstrapEnqueuesTenInitialPlansFromTenSeeds()
            throws Exception {
        configureRollingMode(5, Config.ROLLING_GENERATION_POLICY_GUIDED);
        FuzzingServer server = configuredServer(1000);

        Packet firstPacket = server.getOneTest();

        assertTrue(firstPacket instanceof TestPlanPacket);
        List<TestPlanPacket> bootstrapPackets = collectPackets(
                (TestPlanPacket) firstPacket, queuedTestPlanPackets(server));
        assertEquals(10, bootstrapPackets.size());
        assertEquals(10, server.rollingSeedCorpus.size());

        Set<String> configNames = new HashSet<>();
        for (int i = 0; i < bootstrapPackets.size(); i++) {
            TestPlanPacket packet = bootstrapPackets.get(i);
            assertEquals(i, packet.testPacketID);
            assertNotNull(packet.getTestPlan());
            assertNotNull(packet.getTestPlan().seed);
            assertEquals(-1, packet.getTestPlan().lineageTestId);
            assertTrue(configNames.add(packet.configFileName));
        }
        assertEquals(10, configNames.size());
    }

    @Test
    void pureRandomRollingPolicyBypassesGuidedBootstrap() throws Exception {
        configureRollingMode(5, Config.ROLLING_GENERATION_POLICY_PURE_RANDOM);
        Config.getConf().testPlanGenerationNum = 2;
        FuzzingServer server = configuredServer(2000);

        Packet firstPacket = server.getOneTest();

        assertTrue(firstPacket instanceof TestPlanPacket);
        assertEquals(0, server.rollingSeedCorpus.size());
        assertEquals(1, queuedTestPlanPackets(server).size());
    }

    private static void configureRollingMode(int mode, String policy) {
        Config.Configuration conf = Config.getConf();
        conf.system = "hdfs";
        conf.originalVersion = "old";
        conf.upgradedVersion = "new";
        conf.configDir = "build/test-configs";
        conf.failureDir = "build/test-verification/mode56-bootstrap";
        conf.testingMode = mode;
        conf.rollingGenerationPolicy = policy;
        conf.nodeNum = 3;
        conf.useBranchCoverage = true;
        conf.useTrace = true;
        conf.useFormatCoverage = false;
        conf.enableObservabilityArtifacts = false;
        conf.faultMaxNum = 0;
        conf.testDowngrade = false;
        conf.fullStopUpgradeWithFaults = false;
        conf.MIN_CMD_SEQ_LEN = 3;
        conf.MAX_CMD_SEQ_LEN = 5;
        conf.MAX_READ_CMD_SEQ_LEN = 5;
        conf.enable_HDFS_READ_CMD_SEQ_LEN = true;
        conf.MIN_HDFS_READ_CMD_SEQ_LEN = 3;
        conf.MAX_HDFS_READ_CMD_SEQ_LEN = 5;
    }

    private static FuzzingServer configuredServer(int firstConfigIdx) {
        FuzzingServer server = new FuzzingServer();
        server.commandPool = new HdfsCommandPool();
        server.stateClass = HdfsState.class;
        server.configGen = new CountingConfigGen(firstConfigIdx);
        return server;
    }

    private static List<TestPlanPacket> collectPackets(TestPlanPacket first,
            Queue<TestPlanPacket> queued) {
        List<TestPlanPacket> packets = new ArrayList<>();
        packets.add(first);
        packets.addAll(queued);
        return packets;
    }

    @SuppressWarnings("unchecked")
    private static Queue<TestPlanPacket> queuedTestPlanPackets(
            FuzzingServer server)
            throws Exception {
        Field field = FuzzingServer.class.getDeclaredField("testPlanPackets");
        field.setAccessible(true);
        return (Queue<TestPlanPacket>) field.get(server);
    }

    private static final class CountingConfigGen extends ConfigGen {
        private int nextConfigIdx;

        private CountingConfigGen(int firstConfigIdx) {
            this.nextConfigIdx = firstConfigIdx;
        }

        @Override
        public int generateConfig() {
            return nextConfigIdx++;
        }

        @Override
        public void updateConfigBlackList() {
        }

        @Override
        public void initUpgradeFileGenerator() {
        }

        @Override
        public void initSingleFileGenerator() {
        }
    }
}
