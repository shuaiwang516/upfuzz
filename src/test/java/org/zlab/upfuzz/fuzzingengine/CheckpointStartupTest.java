package org.zlab.upfuzz.fuzzingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.zlab.upfuzz.docker.DockerCluster;
import org.zlab.upfuzz.fuzzingengine.executor.NullExecutor;

class CheckpointStartupTest {

    @Test
    void hbaseReusableCheckpointCacheContainsOnlyMainNodes() {
        Map<String, String> imageNames = CheckpointStartup
                .checkpointCacheImageNames("hbase", "cachekey", 3);

        assertEquals(3, imageNames.size());
        assertTrue(imageNames.containsKey(
                DockerCluster.checkpointImageKeyForMainNode(0)));
        assertTrue(imageNames.containsKey(
                DockerCluster.checkpointImageKeyForMainNode(1)));
        assertTrue(imageNames.containsKey(
                DockerCluster.checkpointImageKeyForMainNode(2)));
        assertFalse(imageNames.containsKey(
                DockerCluster.checkpointImageKeyForExtraNode(100)));
    }

    @Test
    void hdfsRollingCheckpointPrefixStopsSecondaryNameNode() {
        NullExecutor executor = new NullExecutor();
        executor.systemID = "hdfs";

        assertTrue(CheckpointStartup.shouldStopHdfsSNNBeforeCheckpointUpgrade(
                executor, FuzzingClient.LANE_ROLLING));
        assertFalse(CheckpointStartup.shouldStopHdfsSNNBeforeCheckpointUpgrade(
                executor, FuzzingClient.LANE_ONLY_OLD));

        executor.systemID = "cassandra";
        assertFalse(CheckpointStartup.shouldStopHdfsSNNBeforeCheckpointUpgrade(
                executor, FuzzingClient.LANE_ROLLING));
    }

    @Test
    void ephemeralCheckpointImagesAreRemovedAfterCreation() {
        assertFalse(CheckpointStartup.shouldRemoveCheckpointImagesAfterStartup(
                false, false));
        assertTrue(CheckpointStartup.shouldRemoveCheckpointImagesAfterStartup(
                true, false));
        assertFalse(CheckpointStartup.shouldRemoveCheckpointImagesAfterStartup(
                true, true));
    }
}
