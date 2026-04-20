package org.zlab.upfuzz.fuzzingengine.trace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;
import org.zlab.net.tracker.classifier.ProtocolFamily;
import org.zlab.net.tracker.classifier.ProtocolFamilyClassifier;

/**
 * Phase 5 long-tail family-map profile loader.
 *
 * <p>Reads a per-system per-version YAML profile produced by
 * {@code nettrace-shuai/rupfuzz-nettrace/scripts/generate_family_inventories.sh}
 * and registers it with {@link ProtocolFamilyClassifier#setLongTailOverride}.
 * The override only fills in entries the live classifier currently returns
 * as {@link ProtocolFamily#UNKNOWN} — it never demotes an already-classified
 * family. This satisfies the Phase 5 plan rule "long-tail verb / method
 * coverage belongs in profiles, not in the first-cut classifier code".
 *
 * <p>Profile YAML schema (minimum viable):
 * <pre>
 * id: hadoop-3.3.6-family-map
 * description: HDFS 3.3.6 long-tail map
 * system: hdfs
 * familyMap:
 *   # HDFS / HBase entries: (rpcService, rpcMethod) keyed
 *   - rpcService: ClientNamenodeProtocol
 *     rpcMethod: getServerDefaults
 *     family: HDFS_CLIENT_NAMESPACE_MUTATION
 *
 *   # Cassandra entries: messageType (verb) keyed — Cassandra leaves
 *   # rpcService/rpcMethod null and carries the verb name in messageType.
 *   - messageType: PAXOS2_PROPOSE_REQ
 *     family: CASSANDRA_PAXOS
 * </pre>
 *
 * <p>Lookup priority on each call:
 * <ol>
 *   <li>System filter: when {@code this.system} is set and the runtime
 *       {@code system} disagrees, return {@code null} (no override).</li>
 *   <li>{@code messageType} index (case-insensitive): used by Cassandra
 *       and any system that classifies on the verb / message-type alone.</li>
 *   <li>{@code (rpcService, rpcMethod)} index (case-insensitive): used by
 *       HDFS / HBase. Wildcard fallback on {@code rpcMethod=null}
 *       within a service is also tried.</li>
 * </ol>
 *
 * <p>Values that do not match a known {@link ProtocolFamily} constant are
 * ignored with a WARN log. An entry with neither {@code messageType} nor
 * {@code rpcService} is skipped.
 */
public final class VersionAwareFamilyProfile
        implements ProtocolFamilyClassifier.LongTailOverride {

    private static final Logger logger = LogManager
            .getLogger(VersionAwareFamilyProfile.class);

    private final String id;
    private final String description;
    private final String system;
    /** Index of (rpcService, rpcMethod) keyed entries (HDFS / HBase). */
    private final Map<Key, ProtocolFamily> serviceMethodMap;
    /** Index of messageType-keyed entries (Cassandra verbs). */
    private final Map<String, ProtocolFamily> messageTypeMap;

    private VersionAwareFamilyProfile(String id, String description,
            String system,
            Map<Key, ProtocolFamily> serviceMethodMap,
            Map<String, ProtocolFamily> messageTypeMap) {
        this.id = id;
        this.description = description;
        this.system = system;
        this.serviceMethodMap = serviceMethodMap;
        this.messageTypeMap = messageTypeMap;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public String system() {
        return system;
    }

    /** Total number of usable entries across both index maps. */
    public int size() {
        return serviceMethodMap.size() + messageTypeMap.size();
    }

    /** Entries keyed on {@code (rpcService, rpcMethod)}. */
    public int serviceMethodEntryCount() {
        return serviceMethodMap.size();
    }

    /** Entries keyed on {@code messageType} (Cassandra verbs). */
    public int messageTypeEntryCount() {
        return messageTypeMap.size();
    }

    @Override
    public ProtocolFamily lookup(String system, String messageType,
            String rpcService,
            String rpcMethod, String messageKind, String payloadType) {
        if (this.system != null && system != null
                && !this.system.equalsIgnoreCase(system)) {
            return null;
        }
        // messageType-keyed lookup first: Cassandra verbs land here, and
        // any system that the live classifier dispatches off the verb /
        // message type can be extended via this path.
        if (messageType != null && !messageTypeMap.isEmpty()) {
            ProtocolFamily byVerb = messageTypeMap
                    .get(messageType.trim().toUpperCase(Locale.ROOT));
            if (byVerb != null) {
                return byVerb;
            }
        }
        if (rpcService != null && !serviceMethodMap.isEmpty()) {
            ProtocolFamily exact = serviceMethodMap
                    .get(new Key(rpcService, rpcMethod));
            if (exact != null) {
                return exact;
            }
            if (rpcMethod != null) {
                ProtocolFamily wildcardMethod = serviceMethodMap
                        .get(new Key(rpcService, null));
                if (wildcardMethod != null) {
                    return wildcardMethod;
                }
            }
        }
        return null;
    }

    /**
     * Load a profile from {@code path} and register it as the active
     * long-tail override on {@link ProtocolFamilyClassifier}. Replaces any
     * previously registered override.
     *
     * @return the loaded profile.
     * @throws IOException if the file cannot be read or parsed.
     * @throws IllegalArgumentException if the YAML is missing required
     *             fields ({@code id}, {@code familyMap}).
     */
    public static VersionAwareFamilyProfile loadAndRegister(Path path)
            throws IOException {
        Objects.requireNonNull(path, "path");
        VersionAwareFamilyProfile profile = load(path);
        ProtocolFamilyClassifier.setLongTailOverride(profile);
        logger.info(
                "[Phase5] Registered long-tail family profile id={} entries={} (svc/method={}, messageType={}) from {}",
                profile.id, profile.size(), profile.serviceMethodEntryCount(),
                profile.messageTypeEntryCount(), path);
        return profile;
    }

    /**
     * Parse a profile from {@code path} without registering it. Useful for
     * unit tests and replay.
     */
    public static VersionAwareFamilyProfile load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream in = Files.newInputStream(path)) {
            return parse(new Yaml().load(in));
        }
    }

    static VersionAwareFamilyProfile parse(Object yamlRoot) {
        if (!(yamlRoot instanceof Map)) {
            throw new IllegalArgumentException(
                    "family-map profile root must be a YAML mapping; got "
                            + (yamlRoot == null ? "null"
                                    : yamlRoot.getClass().getName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) yamlRoot;
        String id = stringValue(root, "id");
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "family-map profile is missing required 'id' field");
        }
        String description = stringValue(root, "description");
        String system = stringValue(root, "system");
        Object rawEntries = root.get("familyMap");
        if (!(rawEntries instanceof List)) {
            throw new IllegalArgumentException(
                    "family-map profile is missing required 'familyMap' list");
        }
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) rawEntries;
        Map<Key, ProtocolFamily> serviceMethodMap = new HashMap<>();
        Map<String, ProtocolFamily> messageTypeMap = new HashMap<>();
        int skipped = 0;
        for (Object entry : entries) {
            if (!(entry instanceof Map)) {
                skipped++;
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) entry;
            String mtype = stringValue(row, "messageType");
            String svc = stringValue(row, "rpcService");
            String mth = stringValue(row, "rpcMethod");
            String fam = stringValue(row, "family");
            if (fam == null) {
                skipped++;
                continue;
            }
            // An entry MUST identify itself by either messageType (verb-keyed,
            // typically Cassandra) or rpcService (service+method-keyed,
            // HDFS / HBase). Without either the entry is unreachable.
            if (mtype == null && svc == null) {
                logger.warn(
                        "[Phase5] Skipping family-map entry with neither messageType nor rpcService (family={})",
                        fam);
                skipped++;
                continue;
            }
            ProtocolFamily resolved;
            try {
                resolved = ProtocolFamily
                        .valueOf(fam.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                logger.warn(
                        "[Phase5] Skipping family-map entry with unknown family '{}' for messageType={} svc={}:{}",
                        fam, mtype, svc, mth);
                skipped++;
                continue;
            }
            if (mtype != null) {
                messageTypeMap.put(mtype.trim().toUpperCase(Locale.ROOT),
                        resolved);
            } else {
                serviceMethodMap.put(new Key(svc, mth), resolved);
            }
        }
        if (skipped > 0) {
            logger.warn(
                    "[Phase5] Skipped {} malformed entries in family-map profile id={}",
                    skipped, id);
        }
        if (serviceMethodMap.isEmpty() && messageTypeMap.isEmpty()) {
            throw new IllegalArgumentException(
                    "family-map profile id=" + id + " has no usable entries");
        }
        return new VersionAwareFamilyProfile(id, description, system,
                serviceMethodMap, messageTypeMap);
    }

    private static String stringValue(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String normalize(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }

    private static final class Key {
        final String rpcService;
        final String rpcMethod;

        Key(String rpcService, String rpcMethod) {
            this.rpcService = normalize(rpcService);
            this.rpcMethod = normalize(rpcMethod);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return Objects.equals(rpcService, other.rpcService)
                    && Objects.equals(rpcMethod, other.rpcMethod);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rpcService, rpcMethod);
        }

        @Override
        public String toString() {
            return "Key{" + rpcService + "," + rpcMethod + "}";
        }
    }
}
