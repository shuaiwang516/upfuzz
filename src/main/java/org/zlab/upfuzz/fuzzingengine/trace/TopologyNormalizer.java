package org.zlab.upfuzz.fuzzingengine.trace;

import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.TraceEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps raw {@code nodeId} / {@code peerId} strings (IPs, hostnames,
 * container IDs, {@code <executorID>-N<idx>}) to stable role names and
 * node indices for a single lane's cluster topology.
 *
 * <p>Phase 0 added index-aware registration ({@link #registerNode(int,
 * String, String...)}) so the executor can bind every IP / hostname /
 * alias to the node index that owns it. The older {@link
 * #registerMapping(String, String)} API is preserved so callers that only
 * know the role (historical behavior) continue to work; those mappings
 * surface as {@link TopologySnapshot} role-to-index sets when the caller
 * later pairs them with a known node index.
 *
 * <p>The matching {@link #snapshot()} is attached to every
 * {@link org.zlab.upfuzz.fuzzingengine.packet.TestPlanFeedbackPacket} so
 * the fuzzing server can run {@link TopologySnapshot#resolveEndpoint(String)}
 * against raw peer ids in each {@link TraceEntry} without re-discovering
 * IPs. See {@link ResolvedTraceEndpoint} for the resolution contract.
 */
public class TopologyNormalizer {

    private final Map<String, String> idToRole = new LinkedHashMap<>();
    private final Map<String, Integer> idToIndex = new LinkedHashMap<>();
    private final Map<Integer, String> indexToRole = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> roleToIndices = new LinkedHashMap<>();

    /**
     * Register a raw id (IP, hostname, alias) that does not carry a known
     * node index. Kept for historical callers that only expose a role;
     * these mappings participate in role-based disambiguation but never
     * populate {@link TopologySnapshot#idToIndex()}.
     */
    public void registerMapping(String rawId, String role) {
        if (rawId == null || rawId.isEmpty()) {
            return;
        }
        if (role == null || role.isEmpty()) {
            return;
        }
        putId(rawId, role);
        if (rawId.startsWith("/")) {
            putId(rawId.substring(1), role);
        }
    }

    /**
     * Phase 0: register a full node entry — role plus every raw id that
     * points to this node (IP, hostname, container alias, and the
     * {@code <executorID>-N<idx>} form emitted by
     * {@code NET_TRACE_NODE_ID}). All raw ids land in {@link
     * TopologySnapshot#idToIndex()} so the server can resolve peers to a
     * specific node even when the hook recorded only an IP.
     */
    public void registerNode(int nodeIndex, String role, String... rawIds) {
        if (nodeIndex < 0) {
            return;
        }
        String resolvedRole = role == null ? "" : role;
        indexToRole.put(nodeIndex, resolvedRole);
        if (!resolvedRole.isEmpty()) {
            roleToIndices
                    .computeIfAbsent(resolvedRole, k -> new HashSet<>())
                    .add(nodeIndex);
        }
        if (rawIds == null) {
            return;
        }
        for (String rawId : rawIds) {
            if (rawId == null || rawId.isEmpty()) {
                continue;
            }
            putId(rawId, resolvedRole);
            bindIndex(rawId, nodeIndex);
            if (rawId.startsWith("/")) {
                String noSlash = rawId.substring(1);
                putId(noSlash, resolvedRole);
                bindIndex(noSlash, nodeIndex);
            }
            int colon = rawId.lastIndexOf(':');
            if (colon > 0) {
                String noPort = rawId.substring(0, colon);
                putId(noPort, resolvedRole);
                bindIndex(noPort, nodeIndex);
            }
        }
    }

    /**
     * Phase 0: register a raw id / role pair and bind the id to a specific
     * node index. Used when the caller knows the index but has a one-off
     * alias to register after the main {@link #registerNode(int, String,
     * String...)} call (e.g., a late-discovered hostname).
     */
    public void registerMappingWithIndex(String rawId, String role,
            int nodeIndex) {
        registerMapping(rawId, role);
        if (rawId == null || rawId.isEmpty() || nodeIndex < 0) {
            return;
        }
        bindIndex(rawId, nodeIndex);
        indexToRole.putIfAbsent(nodeIndex, role == null ? "" : role);
        if (role != null && !role.isEmpty()) {
            roleToIndices.computeIfAbsent(role, k -> new HashSet<>())
                    .add(nodeIndex);
        }
    }

    /**
     * Resolve a raw id to a role. Returns the raw id unchanged when no
     * mapping exists so legacy callers continue to see the prior
     * "role-or-raw" behavior.
     */
    public String resolve(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return "UNKNOWN";
        }
        String normalized = rawId.startsWith("/") ? rawId.substring(1) : rawId;
        int colonIdx = normalized.lastIndexOf(':');
        String withoutPort = (colonIdx > 0)
                ? normalized.substring(0, colonIdx)
                : null;

        String role = idToRole.get(normalized);
        if (role != null) {
            return role;
        }
        if (withoutPort != null) {
            role = idToRole.get(withoutPort);
            if (role != null) {
                return role;
            }
        }
        return rawId;
    }

    /**
     * Phase 0: topology-aware endpoint resolution. Returns a
     * {@link ResolvedTraceEndpoint} that reports whether the raw id
     * resolves to a single node (via id lookup, numeric parsing, or a
     * unique role) or only to a shared role (ambiguous).
     */
    public ResolvedTraceEndpoint resolveEndpoint(String rawId) {
        return snapshot().resolveEndpoint(rawId);
    }

    /**
     * Apply topology normalization to a trace: fill in {@code peerRole} /
     * {@code nodeRole} on each entry where the field is missing but the
     * raw id can be resolved. Returns a new {@link Trace} so callers
     * never mutate entries in place.
     */
    public Trace normalizeTrace(Trace trace) {
        if (trace == null) {
            return null;
        }
        List<TraceEntry> entries = trace.getTraceEntries();
        Trace result = new Trace();
        for (TraceEntry entry : entries) {
            String resolvedPeerRole = entry.peerRole;
            if ((resolvedPeerRole == null || resolvedPeerRole.isEmpty()
                    || "null".equals(resolvedPeerRole))
                    && entry.peerId != null) {
                String resolved = resolve(entry.peerId);
                if (!resolved.equals(entry.peerId)) {
                    resolvedPeerRole = resolved;
                }
            }
            String resolvedNodeRole = entry.nodeRole;
            if ((resolvedNodeRole == null || resolvedNodeRole.isEmpty()
                    || "null".equals(resolvedNodeRole))
                    && entry.nodeId != null) {
                String resolved = resolve(entry.nodeId);
                if (!resolved.equals(entry.nodeId)) {
                    resolvedNodeRole = resolved;
                }
            }
            if (differs(entry.nodeRole, resolvedNodeRole)
                    || differs(entry.peerRole, resolvedPeerRole)) {
                result.addEntry(new TraceEntry(entry.id, entry.methodName,
                        entry.hashcode,
                        entry.eventType, entry.changedMessage, entry.timestamp,
                        entry.timestampNanos,
                        entry.nodeId, entry.peerId, resolvedNodeRole,
                        resolvedPeerRole, entry.channel,
                        entry.protocol, entry.messageType, entry.messageVersion,
                        entry.rpcService, entry.rpcMethod, entry.messageKind,
                        entry.logicalMessageId, entry.deliveryId,
                        entry.fanoutType,
                        entry.targetCount, entry.messageShapeHash,
                        entry.messageValueHash,
                        entry.messageKey, entry.messageSummary, entry.timedOut,
                        entry.beforeExecPathHash, entry.beforeExecPath,
                        entry.afterExecPathHash,
                        entry.afterExecPath, entry.log));
            } else {
                result.addEntry(entry);
            }
        }
        return result;
    }

    /**
     * Phase 0: produce an immutable topology snapshot suitable for
     * transport (see {@link TopologySnapshot}). The snapshot is
     * independent of future mutations to this normalizer.
     */
    public TopologySnapshot snapshot() {
        Map<Integer, String> indexCopy = new LinkedHashMap<>(indexToRole);
        Map<String, Set<Integer>> roleCopy = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> e : roleToIndices.entrySet()) {
            roleCopy.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        Map<String, Integer> idCopy = new LinkedHashMap<>(idToIndex);
        return new TopologySnapshot(idCopy, indexCopy, roleCopy);
    }

    /** Unmodifiable view of the id-to-role mappings. Test-only helper. */
    public Map<String, String> idToRoleView() {
        return Collections.unmodifiableMap(idToRole);
    }

    private void putId(String rawId, String role) {
        if (rawId == null || role == null) {
            return;
        }
        idToRole.put(rawId, role);
    }

    private void bindIndex(String rawId, int nodeIndex) {
        if (rawId == null || rawId.isEmpty() || nodeIndex < 0) {
            return;
        }
        idToIndex.put(rawId, nodeIndex);
    }

    private static boolean differs(String a, String b) {
        if (a == null) {
            return b != null;
        }
        return !a.equals(b);
    }

    public int mappingCount() {
        return idToRole.size();
    }

    /** Phase 0 helper: number of node indices registered via {@link #registerNode}. */
    public int registeredNodeCount() {
        return indexToRole.size();
    }

    /** Phase 0 helper: view of registered aliases for a given node. */
    public List<String> aliasesForIndex(int nodeIndex) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> e : idToIndex.entrySet()) {
            if (e.getValue() != null && e.getValue() == nodeIndex) {
                result.add(e.getKey());
            }
        }
        return result.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(result);
    }

    /** Testing helper used only by {@link TopologyNormalizer}'s own tests. */
    static List<String> emptyAliasList() {
        return Collections.unmodifiableList(Arrays.asList());
    }
}
