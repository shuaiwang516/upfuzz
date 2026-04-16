package org.zlab.upfuzz.fuzzingengine.server.observability;

import java.util.EnumMap;
import java.util.Map;

/**
 * Phase 3 scheduler snapshot row. Emitted once per completed
 * differential round so offline replay can see how queue occupancy,
 * dequeues, mutation budget, and descendant payoff shift as the
 * campaign runs.
 *
 * <p>The row holds parallel arrays keyed by {@link SchedulerClass} —
 * the <em>internal</em> scheduler lane, not the admission-facing
 * priority label. This is the single source of truth for "how much
 * did the repro-confirm lane do this round?" — the admission-label
 * priority class is still recorded per-row on
 * {@link AdmissionSummaryRow} and {@link QueueActivityRow}.
 *
 * <p>Every lane slot is always emitted (zero-filled) so the CSV
 * schema is fixed and downstream pandas/csvkit loads do not need
 * special handling for empty classes.
 *
 * <p>Counters are emitted as <em>cumulative</em> values (counts since
 * campaign start) rather than per-round deltas — parsers derive deltas
 * by diffing consecutive rows. Occupancy is the instantaneous queue
 * size at the moment the row was emitted.
 */
public final class SchedulerMetricsRow {
    public final long roundId;
    public final int testPacketId;

    public final Map<SchedulerClass, Integer> occupancyByClass;
    public final Map<SchedulerClass, Long> enqueuesByClass;
    public final Map<SchedulerClass, Long> dequeuesByClass;
    public final Map<SchedulerClass, Long> mutationBudgetSpentByClass;
    public final Map<SchedulerClass, Long> branchPayoffByClass;
    public final Map<SchedulerClass, Long> strongPayoffByClass;
    public final Map<SchedulerClass, Long> weakPayoffByClass;
    public final Map<SchedulerClass, Long> dedupCollisionsByClass;
    public final Map<SchedulerClass, Long> decayDemotionsByClass;

    public SchedulerMetricsRow(
            long roundId,
            int testPacketId,
            Map<SchedulerClass, Integer> occupancyByClass,
            Map<SchedulerClass, Long> enqueuesByClass,
            Map<SchedulerClass, Long> dequeuesByClass,
            Map<SchedulerClass, Long> mutationBudgetSpentByClass,
            Map<SchedulerClass, Long> branchPayoffByClass,
            Map<SchedulerClass, Long> strongPayoffByClass,
            Map<SchedulerClass, Long> weakPayoffByClass,
            Map<SchedulerClass, Long> dedupCollisionsByClass,
            Map<SchedulerClass, Long> decayDemotionsByClass) {
        this.roundId = roundId;
        this.testPacketId = testPacketId;
        this.occupancyByClass = copyIntMap(occupancyByClass);
        this.enqueuesByClass = copyLongMap(enqueuesByClass);
        this.dequeuesByClass = copyLongMap(dequeuesByClass);
        this.mutationBudgetSpentByClass = copyLongMap(
                mutationBudgetSpentByClass);
        this.branchPayoffByClass = copyLongMap(branchPayoffByClass);
        this.strongPayoffByClass = copyLongMap(strongPayoffByClass);
        this.weakPayoffByClass = copyLongMap(weakPayoffByClass);
        this.dedupCollisionsByClass = copyLongMap(dedupCollisionsByClass);
        this.decayDemotionsByClass = copyLongMap(decayDemotionsByClass);
    }

    private static Map<SchedulerClass, Integer> copyIntMap(
            Map<SchedulerClass, Integer> src) {
        EnumMap<SchedulerClass, Integer> out = new EnumMap<>(
                SchedulerClass.class);
        for (SchedulerClass c : SchedulerClass.values()) {
            Integer v = src == null ? null : src.get(c);
            out.put(c, v == null ? 0 : v);
        }
        return out;
    }

    private static Map<SchedulerClass, Long> copyLongMap(
            Map<SchedulerClass, Long> src) {
        EnumMap<SchedulerClass, Long> out = new EnumMap<>(
                SchedulerClass.class);
        for (SchedulerClass c : SchedulerClass.values()) {
            Long v = src == null ? null : src.get(c);
            out.put(c, v == null ? 0L : v);
        }
        return out;
    }

    public static String csvHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("round_id,test_packet_id");
        for (SchedulerClass c : SchedulerClass.values()) {
            String base = c.name().toLowerCase();
            sb.append(',').append(base).append("_occupancy");
            sb.append(',').append(base).append("_enqueues");
            sb.append(',').append(base).append("_dequeues");
            sb.append(',').append(base).append("_mutation_budget_spent");
            sb.append(',').append(base).append("_branch_payoff");
            sb.append(',').append(base).append("_strong_payoff");
            sb.append(',').append(base).append("_weak_payoff");
            sb.append(',').append(base).append("_dedup_collisions");
            sb.append(',').append(base).append("_decay_demotions");
        }
        return sb.toString();
    }

    public String toCsvRow() {
        StringBuilder sb = new StringBuilder();
        sb.append(roundId).append(',');
        sb.append(testPacketId);
        for (SchedulerClass c : SchedulerClass.values()) {
            sb.append(',').append(occupancyByClass.get(c));
            sb.append(',').append(enqueuesByClass.get(c));
            sb.append(',').append(dequeuesByClass.get(c));
            sb.append(',').append(mutationBudgetSpentByClass.get(c));
            sb.append(',').append(branchPayoffByClass.get(c));
            sb.append(',').append(strongPayoffByClass.get(c));
            sb.append(',').append(weakPayoffByClass.get(c));
            sb.append(',').append(dedupCollisionsByClass.get(c));
            sb.append(',').append(decayDemotionsByClass.get(c));
        }
        return sb.toString();
    }
}
