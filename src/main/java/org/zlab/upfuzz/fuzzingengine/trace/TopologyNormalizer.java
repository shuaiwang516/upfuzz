package org.zlab.upfuzz.fuzzingengine.trace;

import org.zlab.net.tracker.Trace;
import org.zlab.net.tracker.TraceEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps raw nodeId/peerId strings (IPs, hostnames, container IDs)
 * to stable role names using cluster topology.
 */
public class TopologyNormalizer {

    private final Map<String, String> idToRole = new HashMap<>();

    /**
     * Register a known mapping from the cluster setup.
     * Called after cluster startup with known IP/hostname -> role mappings.
     */
    public void registerMapping(String rawId, String role) {
        if (rawId != null && !rawId.isEmpty() && role != null
                && !role.isEmpty()) {
            idToRole.put(rawId, role);
            // Also register without leading slash (common in
            // InetAddress.toString())
            if (rawId.startsWith("/")) {
                idToRole.put(rawId.substring(1), role);
            }
        }
    }

    /**
     * Resolve a raw ID to a role. Returns the raw ID if no mapping found.
     */
    public String resolve(String rawId) {
        if (rawId == null || rawId.isEmpty())
            return "UNKNOWN";
        // Strip leading slash from InetAddress-style strings
        String normalized = rawId.startsWith("/") ? rawId.substring(1) : rawId;
        // Strip port suffix if present (e.g., "192.168.1.5:7000" ->
        // "192.168.1.5")
        int colonIdx = normalized.lastIndexOf(':');
        String withoutPort = (colonIdx > 0) ? normalized.substring(0, colonIdx)
                : null;

        // Exact match first
        String role = idToRole.get(normalized);
        if (role != null)
            return role;
        if (withoutPort != null) {
            role = idToRole.get(withoutPort);
            if (role != null)
                return role;
        }
        return rawId;
    }

    /**
     * Apply topology normalization to a trace: fill in peerRole on each entry
     * where peerRole is missing but peerId can be resolved.
     * Returns a new Trace with normalized entries.
     */
    public Trace normalizeTrace(Trace trace) {
        if (trace == null)
            return null;
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
            // Also normalize nodeRole if missing
            String resolvedNodeRole = entry.nodeRole;
            if ((resolvedNodeRole == null || resolvedNodeRole.isEmpty()
                    || "null".equals(resolvedNodeRole))
                    && entry.nodeId != null) {
                String resolved = resolve(entry.nodeId);
                if (!resolved.equals(entry.nodeId)) {
                    resolvedNodeRole = resolved;
                }
            }
            // Only create new entry if something changed
            if (differs(entry.nodeRole, resolvedNodeRole)
                    || differs(entry.peerRole, resolvedPeerRole)) {
                result.addEntry(new TraceEntry(entry.id, entry.methodName,
                        entry.hashcode,
                        entry.eventType, entry.changedMessage, entry.timestamp,
                        entry.timestampNanos,
                        entry.nodeId, entry.peerId, resolvedNodeRole,
                        resolvedPeerRole, entry.channel,
                        entry.protocol, entry.messageType, entry.messageVersion,
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

    private static boolean differs(String a, String b) {
        if (a == null)
            return b != null;
        return !a.equals(b);
    }

    public int mappingCount() {
        return idToRole.size();
    }
}
