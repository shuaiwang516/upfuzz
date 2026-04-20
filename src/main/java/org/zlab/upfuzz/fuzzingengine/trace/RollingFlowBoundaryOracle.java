package org.zlab.upfuzz.fuzzingengine.trace;

import java.util.Collections;
import java.util.Set;

import org.zlab.net.tracker.TraceEntry;
import org.zlab.net.tracker.flow.BoundaryOracle;
import org.zlab.net.tracker.flow.FlowBoundaryStatus;

/**
 * Phase 2 adapter between the upfuzz-side topology stack (
 * {@link TopologySnapshot}, {@link ResolvedTraceEndpoint}, and the rolling
 * lane's {@code rawUpgradedNodeSet}) and the ssg-runtime
 * {@link BoundaryOracle} contract consumed by
 * {@link org.zlab.net.tracker.flow.TraceFlowExtractor}.
 *
 * <p>The oracle resolves each {@link TraceEntry}'s source and destination
 * endpoints through the lane's {@link TopologySnapshot} — using the
 * legacy numeric-only {@code extractNodeIndex} logic when no snapshot is
 * attached, matching {@code FuzzingServer.resolveBoundaryEndpoint}. Per
 * entry outcomes:
 *
 * <ul>
 *   <li>both endpoints corroborating and on opposite sides of the
 *       upgraded-node cut → {@link FlowBoundaryStatus#CROSSING}</li>
 *   <li>both endpoints corroborating and on the same side →
 *       {@link FlowBoundaryStatus#NONE}</li>
 *   <li>at least one endpoint resolves only to a shared role →
 *       {@link FlowBoundaryStatus#ROLE_AMBIGUOUS}</li>
 *   <li>at least one endpoint does not resolve at all →
 *       {@link FlowBoundaryStatus#UNRESOLVED}</li>
 * </ul>
 *
 * <p>The oracle keeps boundary logic out of {@code ssg-runtime} so the
 * runtime jar can ship without any knowledge of cluster topology while
 * still producing accurate flow-level boundary corroboration.
 */
public final class RollingFlowBoundaryOracle implements BoundaryOracle {

    private final Set<Integer> upgradedNodeSet;
    private final TopologySnapshot snapshot;

    public RollingFlowBoundaryOracle(Set<Integer> upgradedNodeSet,
            TopologySnapshot snapshot) {
        this.upgradedNodeSet = upgradedNodeSet == null
                ? Collections.<Integer> emptySet()
                : upgradedNodeSet;
        this.snapshot = snapshot;
    }

    @Override
    public FlowBoundaryStatus classify(TraceEntry entry) {
        if (entry == null) {
            return FlowBoundaryStatus.UNRESOLVED;
        }
        ResolvedTraceEndpoint src = resolve(entry.nodeId, entry.nodeRole);
        ResolvedTraceEndpoint dst = resolve(entry.peerId, entry.peerRole);
        if (src.isCorroborating() && dst.isCorroborating()) {
            if (upgradedNodeSet.isEmpty()) {
                return FlowBoundaryStatus.NONE;
            }
            boolean srcUp = upgradedNodeSet.contains(src.nodeIndex);
            boolean dstUp = upgradedNodeSet.contains(dst.nodeIndex);
            return (srcUp ^ dstUp) ? FlowBoundaryStatus.CROSSING
                    : FlowBoundaryStatus.NONE;
        }
        if (src.resolution == ResolvedTraceEndpoint.Resolution.ROLE_AMBIGUOUS
                || dst.resolution == ResolvedTraceEndpoint.Resolution.ROLE_AMBIGUOUS) {
            return FlowBoundaryStatus.ROLE_AMBIGUOUS;
        }
        return FlowBoundaryStatus.UNRESOLVED;
    }

    private ResolvedTraceEndpoint resolve(String rawId, String role) {
        if (snapshot != null) {
            ResolvedTraceEndpoint byId = snapshot.resolveEndpoint(rawId);
            if (byId.isCorroborating()) {
                return byId;
            }
            if (role != null && !role.isEmpty() && !"null".equals(role)) {
                ResolvedTraceEndpoint byRole = snapshot.resolveRole(rawId,
                        role);
                if (byRole.isCorroborating()
                        || byRole.resolution == ResolvedTraceEndpoint.Resolution.ROLE_AMBIGUOUS) {
                    return byRole;
                }
            }
            return byId;
        }
        // Legacy numeric-only path, used by unit tests that pre-date
        // Phase 0 topology propagation.
        int numeric = extractNumeric(rawId);
        if (numeric >= 0) {
            return new ResolvedTraceEndpoint(rawId,
                    ResolvedTraceEndpoint.Resolution.INDEX_RESOLVED, numeric,
                    role);
        }
        return ResolvedTraceEndpoint.unresolved(rawId);
    }

    private static int extractNumeric(String rawId) {
        if (rawId == null || rawId.isEmpty() || "null".equals(rawId)) {
            return -1;
        }
        String candidate;
        int dashN = rawId.lastIndexOf("-N");
        if (dashN >= 0 && dashN + 2 < rawId.length()) {
            candidate = rawId.substring(dashN + 2);
        } else if (rawId.length() >= 2 && rawId.charAt(0) == 'N') {
            candidate = rawId.substring(1);
        } else {
            candidate = rawId;
        }
        try {
            return Integer.parseInt(candidate);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
