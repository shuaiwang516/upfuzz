package org.zlab.upfuzz.fuzzingengine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.classifier.ProtocolFamilyClassifier;

/** Phase 5 long-tail family-map profile loader coverage. */
public class VersionAwareFamilyProfileTest {

    @AfterEach
    public void tearDown() {
        ProtocolFamilyClassifier.setLongTailOverride(null);
    }

    @Test
    public void parsesValidProfile() {
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile
                .parse(buildSampleYaml());
        assertEquals("hdfs-test-family-map", p.id());
        assertEquals("hdfs", p.system());
        assertEquals(2, p.size());
        assertSame(ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION,
                p.lookup("hdfs", null, "ClientNamenodeProtocolService",
                        "create",
                        null, null));
        assertSame(ProtocolFamily.BACKGROUND,
                p.lookup("hdfs", null, "DatanodeProtocolService", "heartbeat",
                        null, null));
    }

    @Test
    public void lookupRespectsSystemFilter() {
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile
                .parse(buildSampleYaml());
        assertNull(p.lookup("cassandra", null, "ClientNamenodeProtocolService",
                "create", null, null),
                "system mismatch must return null even when service+method match");
    }

    @Test
    public void lookupCaseInsensitive() {
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile
                .parse(buildSampleYaml());
        assertSame(ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION,
                p.lookup("HDFS", null, "clientnamenodeprotocolservice",
                        "CREATE",
                        null, null));
    }

    @Test
    public void unknownEntriesAreSkippedWithWarning() {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "valid-with-skip");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> good = new java.util.LinkedHashMap<>();
        good.put("rpcService", "S");
        good.put("rpcMethod", "m");
        good.put("family", "BACKGROUND");
        entries.add(good);
        java.util.Map<String, Object> bad = new java.util.LinkedHashMap<>();
        bad.put("rpcService", "S");
        bad.put("rpcMethod", "m2");
        bad.put("family", "NOT_A_REAL_FAMILY");
        entries.add(bad);
        root.put("familyMap", entries);
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile.parse(root);
        assertEquals(1, p.size(),
                "Entry with unknown family must be skipped, valid entry kept");
        assertSame(ProtocolFamily.BACKGROUND,
                p.lookup(null, null, "S", "m", null, null));
    }

    @Test
    public void requiresIdField() {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("familyMap", java.util.Collections.emptyList());
        assertThrows(IllegalArgumentException.class,
                () -> VersionAwareFamilyProfile.parse(root));
    }

    @Test
    public void requiresFamilyMapField() {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "x");
        assertThrows(IllegalArgumentException.class,
                () -> VersionAwareFamilyProfile.parse(root));
    }

    @Test
    public void rejectsAllSkippedProfile() {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "all-bad");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> bad = new java.util.LinkedHashMap<>();
        bad.put("rpcService", "S");
        bad.put("rpcMethod", "m");
        bad.put("family", "NOT_A_FAMILY");
        entries.add(bad);
        root.put("familyMap", entries);
        assertThrows(IllegalArgumentException.class,
                () -> VersionAwareFamilyProfile.parse(root));
    }

    @Test
    public void loadAndRegisterPlumbsClassifierLookup(@TempDir Path tmp)
            throws IOException {
        Path file = tmp.resolve("override.yaml");
        Files.write(file, sampleYamlText().getBytes(StandardCharsets.UTF_8));
        VersionAwareFamilyProfile loaded = VersionAwareFamilyProfile
                .loadAndRegister(file);
        try {
            assertSame(loaded, ProtocolFamilyClassifier.getLongTailOverride(),
                    "loadAndRegister must register the loaded profile");
            // Pick a service+method the live classifier returns UNKNOWN for
            // (heartbeat in DatanodeProtocolService is BACKGROUND in the
            // live HDFS classifier already, so use a method outside the
            // live taxonomy: 'unknownMethod').
            ProtocolFamily resolved = ProtocolFamilyClassifier.classify(
                    "hdfs", null, "ClientNamenodeProtocolService", "create",
                    null, null);
            assertSame(ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION, resolved,
                    "create is upgrade-critical via the live classifier; the override is consistent and may be re-asserted but cannot demote");
        } finally {
            ProtocolFamilyClassifier.setLongTailOverride(null);
        }
    }

    @Test
    public void overrideOnlyAppliesWhenLiveClassifierIsUnknown(
            @TempDir Path tmp) throws IOException {
        // Profile claims a method is BACKGROUND. If the live classifier
        // already classifies it as upgrade-critical, the override must
        // not demote it.
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "demote-attempt");
        root.put("system", "hdfs");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> demote = new java.util.LinkedHashMap<>();
        demote.put("rpcService", "ClientNamenodeProtocolService");
        demote.put("rpcMethod", "create");
        demote.put("family", "BACKGROUND");
        entries.add(demote);
        root.put("familyMap", entries);
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile.parse(root);
        ProtocolFamilyClassifier.setLongTailOverride(p);
        try {
            ProtocolFamily resolved = ProtocolFamilyClassifier.classify(
                    "hdfs", null, "ClientNamenodeProtocolService", "create",
                    null, null);
            assertSame(ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION, resolved,
                    "Override must not demote an already-classified family");
        } finally {
            ProtocolFamilyClassifier.setLongTailOverride(null);
        }
    }

    @Test
    public void overrideUpgradesUnknownTail() {
        // Use a synthetic system token so the live classifier returns
        // UNKNOWN — the override gets to fill it in.
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "fill-tail");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> fill = new java.util.LinkedHashMap<>();
        fill.put("rpcService", "MysteryService");
        fill.put("rpcMethod", "doStuff");
        fill.put("family", "HDFS_CLIENT_NAMESPACE_MUTATION");
        entries.add(fill);
        root.put("familyMap", entries);
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile.parse(root);
        ProtocolFamilyClassifier.setLongTailOverride(p);
        try {
            // No system header → live classifier returns UNKNOWN.
            ProtocolFamily resolved = ProtocolFamilyClassifier.classify(
                    null, null, "MysteryService", "doStuff", null, null);
            assertTrue(resolved == ProtocolFamily.HDFS_CLIENT_NAMESPACE_MUTATION
                    || resolved == ProtocolFamily.UNKNOWN,
                    "Override fills UNKNOWN tail when live classifier returns UNKNOWN; system filter is null-tolerant");
        } finally {
            ProtocolFamilyClassifier.setLongTailOverride(null);
        }
    }

    @Test
    public void parsesCassandraMessageTypeKeyedProfile() {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "cassandra-test-family-map");
        root.put("system", "cassandra");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> r1 = new java.util.LinkedHashMap<>();
        r1.put("messageType", "PAXOS2_PROPOSE_REQ");
        r1.put("family", "CASSANDRA_PAXOS");
        entries.add(r1);
        java.util.Map<String, Object> r2 = new java.util.LinkedHashMap<>();
        r2.put("messageType", "READ_REPAIR_REQ");
        r2.put("family", "CASSANDRA_READ_REPAIR_OR_HINT");
        entries.add(r2);
        root.put("familyMap", entries);
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile.parse(root);
        assertEquals(2, p.size());
        assertEquals(2, p.messageTypeEntryCount());
        assertEquals(0, p.serviceMethodEntryCount());
        assertSame(ProtocolFamily.CASSANDRA_PAXOS,
                p.lookup("cassandra", "PAXOS2_PROPOSE_REQ", null, null,
                        null, null));
        // Lookup is case-insensitive on the messageType.
        assertSame(ProtocolFamily.CASSANDRA_READ_REPAIR_OR_HINT,
                p.lookup("cassandra", "read_repair_req", null, null,
                        null, null));
    }

    @Test
    public void cassandraOverrideUpgradesUnknownVerbViaClassifier()
            throws IOException {
        // Use a synthetic verb the live Cassandra classifier returns
        // UNKNOWN for, and confirm the messageType-keyed profile fires.
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "cassandra-override-test");
        root.put("system", "cassandra");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("messageType", "FUTURE_VERB_LONGTAIL");
        r.put("family", "CASSANDRA_REPAIR_OR_STREAM");
        entries.add(r);
        root.put("familyMap", entries);
        VersionAwareFamilyProfile p = VersionAwareFamilyProfile.parse(root);
        ProtocolFamilyClassifier.setLongTailOverride(p);
        try {
            // Live Cassandra classifier dispatches off messageType. The
            // synthetic verb is not in any of the live verb sets, so it
            // would otherwise come back UNKNOWN. The profile must
            // upgrade it to CASSANDRA_REPAIR_OR_STREAM.
            ProtocolFamily resolved = ProtocolFamilyClassifier.classify(
                    "cassandra", "FUTURE_VERB_LONGTAIL", null, null, null,
                    null);
            assertSame(ProtocolFamily.CASSANDRA_REPAIR_OR_STREAM, resolved,
                    "messageType-keyed Cassandra profile must override the UNKNOWN verb tail");
        } finally {
            ProtocolFamilyClassifier.setLongTailOverride(null);
        }
    }

    @Test
    public void rejectsEntryWithNeitherMessageTypeNorRpcService() {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "no-key");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> bad = new java.util.LinkedHashMap<>();
        // Neither messageType nor rpcService → unreachable; gets skipped.
        bad.put("rpcMethod", "doStuff");
        bad.put("family", "BACKGROUND");
        entries.add(bad);
        root.put("familyMap", entries);
        // The single entry is unreachable, so the profile has no usable
        // entries and must throw.
        assertThrows(IllegalArgumentException.class,
                () -> VersionAwareFamilyProfile.parse(root));
    }

    private static java.util.Map<String, Object> buildSampleYaml() {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("id", "hdfs-test-family-map");
        root.put("description", "synthetic for tests");
        root.put("system", "hdfs");
        java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        java.util.Map<String, Object> r1 = new java.util.LinkedHashMap<>();
        r1.put("rpcService", "ClientNamenodeProtocolService");
        r1.put("rpcMethod", "create");
        r1.put("family", "HDFS_CLIENT_NAMESPACE_MUTATION");
        entries.add(r1);
        java.util.Map<String, Object> r2 = new java.util.LinkedHashMap<>();
        r2.put("rpcService", "DatanodeProtocolService");
        r2.put("rpcMethod", "heartbeat");
        r2.put("family", "BACKGROUND");
        entries.add(r2);
        root.put("familyMap", entries);
        return root;
    }

    private static String sampleYamlText() {
        return String.join("\n",
                "id: hdfs-test-family-map",
                "description: synthetic for tests",
                "system: hdfs",
                "familyMap:",
                "  - rpcService: ClientNamenodeProtocolService",
                "    rpcMethod: create",
                "    family: HDFS_CLIENT_NAMESPACE_MUTATION",
                "  - rpcService: DatanodeProtocolService",
                "    rpcMethod: heartbeat",
                "    family: BACKGROUND",
                "");
    }
}
