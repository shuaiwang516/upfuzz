package org.zlab.upfuzz.fuzzingengine.trace;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable view of cluster topology captured on the executor side so the
 * server can resolve trace endpoints without re-discovering IPs, hostnames,
 * and container aliases. The executor owns the live {@link
 * TopologyNormalizer} and ships this snapshot alongside {@link
 * org.zlab.upfuzz.fuzzingengine.trace.WindowedTrace} per lane — see
 * {@link org.zlab.upfuzz.fuzzingengine.packet.TestPlanFeedbackPacket#topologySnapshot}.
 *
 * <p>Resolution uses three layers, in this order:
 * <ol>
 *   <li>Exact id lookup: {@code executor-N0}, hostnames, IPs registered by
 *       the executor are keyed directly to the node index.</li>
 *   <li>Numeric parsing: {@code <executor>-N<digits>} / {@code N<digits>}
 *       / bare decimals are parsed to an index when they fall inside the
 *       known node range. This is the Apr15 behavior — preserved as a
 *       fallback because the rolling runtime always emits the
 *       {@code NET_TRACE_NODE_ID=<executor>-N<idx>} form.</li>
 *   <li>Role lookup: a raw id whose explicit mapping carries a role (or
 *       which resolves to a known role via the {@link TopologyNormalizer})
 *       is promoted to {@link ResolvedTraceEndpoint.Resolution#ROLE_UNIQUE}
 *       only when the role identifies a single node in the snapshot.
 *       Otherwise the result is {@link
 *       ResolvedTraceEndpoint.Resolution#ROLE_AMBIGUOUS} — diagnostic
 *       only so Cassandra peer-to-peer traffic and HDFS multi-datanode
 *       traffic cannot silently inflate boundary corroboration.</li>
 * </ol>
 *
 * <p>The snapshot is intentionally small (string maps, bounded by
 * {@code nodeNum} entries per node) so it can piggyback on every trace
 * packet without measurable overhead.
 */
public final class TopologySnapshot implements Serializable {
    private static final long serialVersionUID = 20260419L;

    private final Map<String, Integer> idToIndex;
    private final Map<Integer, String> indexToRole;
    private final Map<String, Set<Integer>> roleToIndices;

    public TopologySnapshot(Map<String, Integer> idToIndex,
            Map<Integer, String> indexToRole,
            Map<String, Set<Integer>> roleToIndices) {
        this.idToIndex = idToIndex == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(idToIndex));
        this.indexToRole = indexToRole == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(indexToRole));
        Map<String, Set<Integer>> copy = new HashMap<>();
        if (roleToIndices != null) {
            for (Map.Entry<String, Set<Integer>> e : roleToIndices.entrySet()) {
                copy.put(e.getKey(),
                        Collections.unmodifiableSet(
                                new HashSet<>(e.getValue())));
            }
        }
        this.roleToIndices = Collections.unmodifiableMap(copy);
    }

    public static TopologySnapshot empty() {
        return new TopologySnapshot(Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap());
    }

    /** Resolve a raw endpoint string to a role-and-index tuple. */
    public ResolvedTraceEndpoint resolveEndpoint(String rawId) {
        if (rawId == null || rawId.isEmpty() || "null".equals(rawId)) {
            return ResolvedTraceEndpoint.unresolved(rawId);
        }
        String normalized = rawId.startsWith("/") ? rawId.substring(1) : rawId;
        String withoutPort = normalized;
        int colon = normalized.lastIndexOf(':');
        if (colon > 0) {
            withoutPort = normalized.substring(0, colon);
        }

        // Tier 1: direct id lookup.
        Integer indexByDirect = idToIndex.get(normalized);
        if (indexByDirect == null && !withoutPort.equals(normalized)) {
            indexByDirect = idToIndex.get(withoutPort);
        }
        if (indexByDirect == null) {
            indexByDirect = idToIndex.get(rawId);
        }
        if (indexByDirect != null) {
            return new ResolvedTraceEndpoint(rawId,
                    ResolvedTraceEndpoint.Resolution.INDEX_RESOLVED,
                    indexByDirect, indexToRole.get(indexByDirect));
        }

        // Tier 2: numeric parsing ("<prefix>-N<digits>", "N<digits>", bare).
        int parsed = tryParseNumericIndex(normalized);
        if (parsed < 0 && !withoutPort.equals(normalized)) {
            parsed = tryParseNumericIndex(withoutPort);
        }
        if (parsed >= 0 && indexToRole.containsKey(parsed)) {
            return new ResolvedTraceEndpoint(rawId,
                    ResolvedTraceEndpoint.Resolution.INDEX_RESOLVED,
                    parsed, indexToRole.get(parsed));
        }

        // Tier 3: role lookup via the raw id itself (already a role name).
        Set<Integer> byRole = roleToIndices.get(normalized);
        if (byRole == null) {
            byRole = roleToIndices.get(rawId);
        }
        if (byRole != null && !byRole.isEmpty()) {
            if (byRole.size() == 1) {
                int uniqueIdx = byRole.iterator().next();
                return new ResolvedTraceEndpoint(rawId,
                        ResolvedTraceEndpoint.Resolution.ROLE_UNIQUE,
                        uniqueIdx, normalized);
            }
            return new ResolvedTraceEndpoint(rawId,
                    ResolvedTraceEndpoint.Resolution.ROLE_AMBIGUOUS,
                    -1, normalized);
        }

        return ResolvedTraceEndpoint.unresolved(rawId);
    }

    /**
     * Role-based resolution path for callers that already have a resolved
     * role (e.g. from {@link org.zlab.net.tracker.TraceEntry#peerRole}).
     * Returns {@link ResolvedTraceEndpoint.Resolution#ROLE_UNIQUE} only
     * when the role uniquely identifies one node in this topology.
     */
    public ResolvedTraceEndpoint resolveRole(String rawId, String role) {
        if (role == null || role.isEmpty() || "null".equals(role)) {
            return resolveEndpoint(rawId);
        }
        Set<Integer> indices = roleToIndices.get(role);
        if (indices == null || indices.isEmpty()) {
            return resolveEndpoint(rawId);
        }
        if (indices.size() == 1) {
            int uniqueIdx = indices.iterator().next();
            return new ResolvedTraceEndpoint(rawId,
                    ResolvedTraceEndpoint.Resolution.ROLE_UNIQUE,
                    uniqueIdx, role);
        }
        return new ResolvedTraceEndpoint(rawId,
                ResolvedTraceEndpoint.Resolution.ROLE_AMBIGUOUS, -1, role);
    }

    public int nodeCount() {
        return indexToRole.size();
    }

    public boolean hasRole(String role) {
        Set<Integer> indices = roleToIndices.get(role);
        return indices != null && !indices.isEmpty();
    }

    public boolean isRoleUnique(String role) {
        Set<Integer> indices = roleToIndices.get(role);
        return indices != null && indices.size() == 1;
    }

    public Map<String, Integer> idToIndex() {
        return idToIndex;
    }

    public Map<Integer, String> indexToRole() {
        return indexToRole;
    }

    public Map<String, Set<Integer>> roleToIndices() {
        return roleToIndices;
    }

    private static int tryParseNumericIndex(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return -1;
        }
        String digits;
        int dashN = candidate.lastIndexOf("-N");
        if (dashN >= 0 && dashN + 2 < candidate.length()) {
            digits = candidate.substring(dashN + 2);
        } else if (candidate.length() >= 2 && candidate.charAt(0) == 'N') {
            digits = candidate.substring(1);
        } else {
            digits = candidate;
        }
        try {
            int parsed = Integer.parseInt(digits);
            return parsed >= 0 ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
