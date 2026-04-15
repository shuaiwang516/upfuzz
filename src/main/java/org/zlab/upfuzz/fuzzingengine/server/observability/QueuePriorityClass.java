package org.zlab.upfuzz.fuzzingengine.server.observability;

/**
 * Priority class assigned to a plan entering the short-term queue.
 *
 * <p>Phase 0 only records the class. The short-term scheduler is still a
 * plain FIFO — Phase 3 will consume these labels to implement a
 * stratified or weighted queue. Recording the class now lets offline
 * analyses answer "how much queue energy did weak plans consume?"
 * against Apr15 observability artifacts without waiting for the
 * scheduler rewrite.
 *
 * <ul>
 *   <li>{@link #BRANCH_ONLY} — admitted purely because of new branch
 *       coverage.</li>
 *   <li>{@link #BRANCH_AND_STRONG_TRACE} — admitted on both branch
 *       novelty and strong (support-backed, mixed-version-relevant)
 *       trace evidence.</li>
 *   <li>{@link #BRANCH_AND_WEAK_TRACE} — admitted on branch novelty with
 *       some trace corroboration, but the trace evidence did not reach
 *       the strong bar.</li>
 *   <li>{@link #TRACE_ONLY_STRONG} — admitted without branch novelty on
 *       strong trace evidence. This is the Phase 3 "shadow lane"
 *       class.</li>
 *   <li>{@link #TRACE_ONLY_WEAK} — admitted without branch novelty on
 *       weak or unsupported trace evidence. Phase 3 will aggressively
 *       shrink mutation energy for this class.</li>
 *   <li>{@link #UNKNOWN} — unlabeled (default).</li>
 * </ul>
 */
public enum QueuePriorityClass {
    BRANCH_ONLY, BRANCH_AND_STRONG_TRACE, BRANCH_AND_WEAK_TRACE, TRACE_ONLY_STRONG, TRACE_ONLY_WEAK, UNKNOWN;
}
