package org.zlab.upfuzz.fuzzingengine.server;

import org.zlab.upfuzz.fuzzingengine.server.observability.StructuredCandidateStrength;

/**
 * Phase 1 structured outcome of the checker-D cross-cluster structured
 * comparison. Supersedes the Phase 0 {@code String}-or-{@code null}
 * return value of {@code FuzzingServer.checkCrossClusterInconsistencyStructured}.
 *
 * <p>The router needs to know three things at once:
 * <ul>
 *   <li>Did rolling diverge from both baselines while the baselines
 *       agreed? ({@link #diverged})</li>
 *   <li>How confident is that divergence? ({@link #strength})</li>
 *   <li>What should be written to the candidate report file?
 *       ({@link #report})</li>
 * </ul>
 *
 * <p>Phase 1 candidate routing in
 * {@link FuzzingServer#classifyAndSaveFailures} uses
 * {@link #strength} to split reports between the {@code failure/candidate/strong/}
 * and {@code failure/candidate/weak/} subdirectories, and only
 * {@link StructuredCandidateStrength#STRONG} outcomes are allowed to
 * promote probation seeds via
 * {@code RollingSeedCorpus#notifyStructuredCandidatePayoff}.
 */
public final class CrossClusterComparisonOutcome {

    public final boolean diverged;
    public final StructuredCandidateStrength strength;
    public final String report;
    public final boolean containsUnknown;
    public final boolean containsDaemonError;

    public CrossClusterComparisonOutcome(
            boolean diverged,
            StructuredCandidateStrength strength,
            String report,
            boolean containsUnknown,
            boolean containsDaemonError) {
        this.diverged = diverged;
        this.strength = strength == null ? StructuredCandidateStrength.NONE
                : strength;
        this.report = report;
        this.containsUnknown = containsUnknown;
        this.containsDaemonError = containsDaemonError;
    }

    public static CrossClusterComparisonOutcome none() {
        return new CrossClusterComparisonOutcome(
                /*diverged*/ false,
                StructuredCandidateStrength.NONE,
                /*report*/ null,
                /*containsUnknown*/ false,
                /*containsDaemonError*/ false);
    }

    public boolean isStrong() {
        return diverged && strength == StructuredCandidateStrength.STRONG;
    }

    public boolean isWeak() {
        return diverged && strength == StructuredCandidateStrength.WEAK;
    }
}
