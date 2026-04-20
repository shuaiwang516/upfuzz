package org.zlab.upfuzz.fuzzingengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zlab.net.tracker.classifier.ProtocolFamilyClassifier;

/** Phase 5 preset application coverage. */
public class TraceSystemPresetTest {

    @AfterEach
    public void tearDown() {
        // Tests in this class call Config.setInstance which mutates the
        // JVM-global classifier override registry. Ensure each test
        // starts from a clean slate, regardless of test ordering.
        ProtocolFamilyClassifier.setLongTailOverride(null);
    }

    @Test
    public void autoResolvesFromSystem() {
        assertEquals(TraceSystemPreset.CASSANDRA,
                TraceSystemPreset.AUTO.resolve("cassandra"));
        assertEquals(TraceSystemPreset.HDFS,
                TraceSystemPreset.AUTO.resolve("HDFS"));
        assertEquals(TraceSystemPreset.HBASE,
                TraceSystemPreset.AUTO.resolve(" hbase "));
        assertEquals(TraceSystemPreset.GENERIC,
                TraceSystemPreset.AUTO.resolve(null));
        assertEquals(TraceSystemPreset.GENERIC,
                TraceSystemPreset.AUTO.resolve("ozone"));
    }

    @Test
    public void resolveReturnsSelfWhenNotAuto() {
        assertEquals(TraceSystemPreset.HDFS,
                TraceSystemPreset.HDFS.resolve("cassandra"));
        assertEquals(TraceSystemPreset.GENERIC,
                TraceSystemPreset.GENERIC.resolve(null));
    }

    @Test
    public void cassandraPresetTuningMatchesPlan() {
        Config.Configuration c = new Config.Configuration();
        TraceSystemPreset.CASSANDRA.applyTo(c);
        assertEquals(0.40, c.traceStrongScoreThreshold, 1e-9);
        assertEquals(0.10, c.traceWeakScoreThreshold, 1e-9);
        assertEquals(0.08, c.traceBackgroundCap, 1e-9);
        assertEquals(0.15, c.traceBackgroundFamilyWeight, 1e-9);
        assertEquals(0, c.traceOnlyAdmissionCapPerRound);
        assertEquals(0, c.traceOnlyAdmissionCapPer100Rounds);
        assertFalse(c.enableLowerConfidenceTraceAdmission,
                "Cassandra preset must keep lower-confidence trace admission off");
        assertEquals(8, c.mainExploitQueueWeight);
        assertEquals(4, c.branchScoutQueueWeight);
        assertEquals(1, c.shadowEvalQueueWeight);
    }

    @Test
    public void hdfsPresetEnablesBoundedTraceAdmission() {
        Config.Configuration c = new Config.Configuration();
        TraceSystemPreset.HDFS.applyTo(c);
        assertEquals(0.32, c.traceStrongScoreThreshold, 1e-9);
        assertEquals(0.10, c.traceBackgroundCap, 1e-9);
        assertEquals(0.15, c.traceBackgroundFamilyWeight, 1e-9);
        assertEquals(1, c.traceOnlyAdmissionCapPerRound,
                "HDFS preset enables bounded trace-only admission per round");
        assertEquals(10, c.traceOnlyAdmissionCapPer100Rounds);
        assertTrue(c.enableLowerConfidenceTraceAdmission,
                "HDFS preset must enable lower-confidence trace admission");
        assertEquals(8, c.mainExploitQueueWeight);
        assertEquals(3, c.branchScoutQueueWeight);
        assertEquals(2, c.shadowEvalQueueWeight);
    }

    @Test
    public void hbasePresetMirrorsCassandraAdmissionOff() {
        Config.Configuration c = new Config.Configuration();
        TraceSystemPreset.HBASE.applyTo(c);
        assertEquals(0.40, c.traceStrongScoreThreshold, 1e-9);
        assertEquals(0.08, c.traceBackgroundCap, 1e-9);
        assertEquals(0, c.traceOnlyAdmissionCapPerRound);
        assertEquals(0, c.traceOnlyAdmissionCapPer100Rounds);
        assertFalse(c.enableLowerConfidenceTraceAdmission);
    }

    @Test
    public void genericPresetIsNoOp() {
        Config.Configuration c = new Config.Configuration();
        // Capture defaults before apply.
        double strongBefore = c.traceStrongScoreThreshold;
        double weakBefore = c.traceWeakScoreThreshold;
        double backgroundCapBefore = c.traceBackgroundCap;
        boolean lowerConfBefore = c.enableLowerConfidenceTraceAdmission;
        TraceSystemPreset.GENERIC.applyTo(c);
        assertEquals(strongBefore, c.traceStrongScoreThreshold, 1e-9);
        assertEquals(weakBefore, c.traceWeakScoreThreshold, 1e-9);
        assertEquals(backgroundCapBefore, c.traceBackgroundCap, 1e-9);
        assertEquals(lowerConfBefore, c.enableLowerConfidenceTraceAdmission);
    }

    @Test
    public void setInstanceAppliesPresetAndResolvesAuto() {
        Config.Configuration c = new Config.Configuration();
        c.system = "hdfs";
        c.traceSystemPreset = TraceSystemPreset.AUTO;
        // Pre-set a non-preset value to ensure the preset overrides it.
        c.traceStrongScoreThreshold = 0.99;
        Config.setInstance(c);
        assertEquals(TraceSystemPreset.HDFS, c.traceSystemPreset,
                "AUTO must be resolved to a concrete preset on setInstance");
        assertEquals(0.32, c.traceStrongScoreThreshold, 1e-9,
                "Preset must override JSON-supplied trace knobs");
    }

    @Test
    public void setInstanceWithGenericPreservesUserKnobs() {
        Config.Configuration c = new Config.Configuration();
        c.system = "cassandra";
        c.traceSystemPreset = TraceSystemPreset.GENERIC;
        c.traceStrongScoreThreshold = 0.99;
        c.traceWeakScoreThreshold = 0.05;
        Config.setInstance(c);
        assertEquals(TraceSystemPreset.GENERIC, c.traceSystemPreset);
        assertEquals(0.99, c.traceStrongScoreThreshold, 1e-9,
                "GENERIC must not override JSON-supplied trace knobs");
        assertEquals(0.05, c.traceWeakScoreThreshold, 1e-9);
    }

    @Test
    public void setInstanceWithNullPresetDefaultsToAuto() {
        Config.Configuration c = new Config.Configuration();
        c.system = "hbase";
        c.traceSystemPreset = null;
        Config.setInstance(c);
        assertEquals(TraceSystemPreset.HBASE, c.traceSystemPreset);
    }

    @Test
    public void setInstanceClearsOverrideWhenSecondConfigOmitsProfile(
            @TempDir Path tmp) throws IOException {
        Path profilePath = tmp.resolve("hdfs-test.yaml");
        Files.write(profilePath, sampleHdfsYaml().getBytes(
                StandardCharsets.UTF_8));

        // First setInstance: register the profile.
        Config.Configuration first = new Config.Configuration();
        first.system = "hdfs";
        first.familyMapProfilePath = profilePath.toString();
        Config.setInstance(first);
        assertNotNull(ProtocolFamilyClassifier.getLongTailOverride(),
                "first setInstance with profile must register the long-tail override");

        // Second setInstance: NO profile path → must clear the override
        // so the next run does not silently inherit the prior profile.
        Config.Configuration second = new Config.Configuration();
        second.system = "cassandra";
        second.familyMapProfilePath = null;
        Config.setInstance(second);
        assertNull(ProtocolFamilyClassifier.getLongTailOverride(),
                "second setInstance without profile must clear the JVM-global override");
    }

    @Test
    public void setInstanceClearsOverrideWhenProfileLoadFails(
            @TempDir Path tmp) throws IOException {
        Path goodProfile = tmp.resolve("hdfs-test.yaml");
        Files.write(goodProfile, sampleHdfsYaml().getBytes(
                StandardCharsets.UTF_8));

        // First setInstance: register the profile.
        Config.Configuration first = new Config.Configuration();
        first.system = "hdfs";
        first.familyMapProfilePath = goodProfile.toString();
        Config.setInstance(first);
        assertNotNull(ProtocolFamilyClassifier.getLongTailOverride());

        // Second setInstance: a path that points at a non-existent file.
        // The loader fails and must clear the override (rather than
        // leaving the previous profile silently active).
        Config.Configuration second = new Config.Configuration();
        second.system = "hdfs";
        second.familyMapProfilePath = tmp.resolve("does-not-exist.yaml")
                .toString();
        Config.setInstance(second);
        assertNull(ProtocolFamilyClassifier.getLongTailOverride(),
                "load failure must clear the previously-registered override");
    }

    @Test
    public void setInstanceReplacesOverrideOnSecondProfile(@TempDir Path tmp)
            throws IOException {
        Path profileA = tmp.resolve("a.yaml");
        Files.write(profileA,
                sampleHdfsYaml().getBytes(StandardCharsets.UTF_8));
        Path profileB = tmp.resolve("b.yaml");
        Files.write(profileB,
                sampleHdfsYaml().replace("hdfs-test-family-map",
                        "hdfs-test-family-map-B")
                        .getBytes(StandardCharsets.UTF_8));

        Config.Configuration first = new Config.Configuration();
        first.system = "hdfs";
        first.familyMapProfilePath = profileA.toString();
        Config.setInstance(first);
        ProtocolFamilyClassifier.LongTailOverride afterFirst = ProtocolFamilyClassifier
                .getLongTailOverride();
        assertNotNull(afterFirst);

        Config.Configuration second = new Config.Configuration();
        second.system = "hdfs";
        second.familyMapProfilePath = profileB.toString();
        Config.setInstance(second);
        ProtocolFamilyClassifier.LongTailOverride afterSecond = ProtocolFamilyClassifier
                .getLongTailOverride();
        assertNotNull(afterSecond);
        assertNotSame(afterFirst, afterSecond,
                "second setInstance must replace the registered override, not retain the first");
    }

    private static String sampleHdfsYaml() {
        return String.join("\n",
                "id: hdfs-test-family-map",
                "system: hdfs",
                "familyMap:",
                "  - rpcService: ClientNamenodeProtocolService",
                "    rpcMethod: create",
                "    family: HDFS_CLIENT_NAMESPACE_MUTATION",
                "");
    }
}
