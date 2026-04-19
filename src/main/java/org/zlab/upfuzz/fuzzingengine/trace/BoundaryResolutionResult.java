package org.zlab.upfuzz.fuzzingengine.trace;

import java.io.Serializable;

/**
 * Aggregated outcome of running {@link ResolvedTraceEndpoint} over every
 * event in a merged rolling-lane {@link org.zlab.net.tracker.Trace}.
 *
 * <p>The Apr16 campaign reported {@code upgraded_boundary_positive_windows
 * = 0} because {@code extractNodeIndex} silently skipped any entry whose
 * peer was an IP or hostname. This aggregate breaks that silence:
 * ambiguous and unresolved endpoints are counted separately so runs can
 * measure how much boundary traffic the legacy counter was dropping.
 *
 * <p>{@link #crossingCount} is the number of events whose <em>both</em>
 * endpoints resolved with enough confidence
 * ({@link ResolvedTraceEndpoint#isCorroborating()}) <em>and</em> landed on
 * opposite sides of the rolling lane's {@code rawUpgradedNodeSet}. Events
 * that resolve but fall within the same version half (both upgraded or
 * both non-upgraded) are not crossings; they are tracked implicitly via
 * {@link #totalEventCount}.
 */
public final class BoundaryResolutionResult implements Serializable {
    private static final long serialVersionUID = 20260419L;

    public final int totalEventCount;
    public final int crossingCount;
    public final int indexResolvedEndpointCount;
    public final int roleResolvedEndpointCount;
    public final int roleAmbiguousEndpointCount;
    public final int unresolvedEndpointCount;

    public BoundaryResolutionResult(int totalEventCount, int crossingCount,
            int indexResolvedEndpointCount,
            int roleResolvedEndpointCount,
            int roleAmbiguousEndpointCount,
            int unresolvedEndpointCount) {
        this.totalEventCount = totalEventCount;
        this.crossingCount = crossingCount;
        this.indexResolvedEndpointCount = indexResolvedEndpointCount;
        this.roleResolvedEndpointCount = roleResolvedEndpointCount;
        this.roleAmbiguousEndpointCount = roleAmbiguousEndpointCount;
        this.unresolvedEndpointCount = unresolvedEndpointCount;
    }

    public static BoundaryResolutionResult empty() {
        return new BoundaryResolutionResult(0, 0, 0, 0, 0, 0);
    }

    public BoundaryResolutionResult add(BoundaryResolutionResult other) {
        if (other == null) {
            return this;
        }
        return new BoundaryResolutionResult(
                totalEventCount + other.totalEventCount,
                crossingCount + other.crossingCount,
                indexResolvedEndpointCount
                        + other.indexResolvedEndpointCount,
                roleResolvedEndpointCount + other.roleResolvedEndpointCount,
                roleAmbiguousEndpointCount
                        + other.roleAmbiguousEndpointCount,
                unresolvedEndpointCount + other.unresolvedEndpointCount);
    }

    @Override
    public String toString() {
        return "BoundaryResolutionResult{events=" + totalEventCount
                + ", crossings=" + crossingCount
                + ", indexEndpoints=" + indexResolvedEndpointCount
                + ", roleEndpoints=" + roleResolvedEndpointCount
                + ", ambiguousEndpoints=" + roleAmbiguousEndpointCount
                + ", unresolvedEndpoints=" + unresolvedEndpointCount + "}";
    }
}
