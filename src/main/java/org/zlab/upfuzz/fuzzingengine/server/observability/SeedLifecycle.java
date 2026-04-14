package org.zlab.upfuzz.fuzzingengine.server.observability;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-seed lifecycle metadata for a corpus-admitted seed.
 *
 * <p>Created when a seed is admitted to the corpus. Counters are mutated
 * later as the seed is selected as a mutation parent and as its descendants
 * produce downstream branch hits or candidate artifacts.
 *
 * <p>Phase 0 uses this record to answer two questions after a run:
 * <ul>
 *   <li>What rule admitted this seed?</li>
 *   <li>Did that seed later pay off in new branch coverage or structured
 *       tri-lane candidates?</li>
 * </ul>
 *
 * <p>Structured (cross-cluster Checker D) and weak (event-failure /
 * error-log) candidate credits are tracked separately because the Phase 2
 * retention policy must be able to ignore the noisy weak categories that
 * Phase 5 will clean up.
 */
public final class SeedLifecycle {
    public final int seedTestId;
    public final long creationRound;
    public final long creationTimestampMs;
    public final AdmissionReason creationReason;
    public final int parentSeedTestId;

    public final AtomicLong timesSelectedAsParent = new AtomicLong(0);
    public final AtomicLong descendantNewBranchHits = new AtomicLong(0);
    public final AtomicLong descendantStructuredCandidateHits = new AtomicLong(
            0);
    public final AtomicLong descendantWeakCandidateHits = new AtomicLong(0);

    public SeedLifecycle(
            int seedTestId,
            long creationRound,
            long creationTimestampMs,
            AdmissionReason creationReason,
            int parentSeedTestId) {
        this.seedTestId = seedTestId;
        this.creationRound = creationRound;
        this.creationTimestampMs = creationTimestampMs;
        this.creationReason = creationReason == null
                ? AdmissionReason.UNKNOWN
                : creationReason;
        this.parentSeedTestId = parentSeedTestId;
    }

    public static String csvHeader() {
        return String.join(",",
                "seed_test_id",
                "creation_round",
                "creation_timestamp_ms",
                "creation_reason",
                "parent_seed_test_id",
                "times_selected_as_parent",
                "descendant_new_branch_hits",
                "descendant_structured_candidate_hits",
                "descendant_weak_candidate_hits");
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(seedTestId).append(',');
        sb.append(creationRound).append(',');
        sb.append(creationTimestampMs).append(',');
        sb.append(creationReason.name()).append(',');
        sb.append(parentSeedTestId).append(',');
        sb.append(timesSelectedAsParent.get()).append(',');
        sb.append(descendantNewBranchHits.get()).append(',');
        sb.append(descendantStructuredCandidateHits.get()).append(',');
        sb.append(descendantWeakCandidateHits.get());
        return sb.toString();
    }
}
