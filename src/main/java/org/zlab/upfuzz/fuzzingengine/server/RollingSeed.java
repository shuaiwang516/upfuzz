package org.zlab.upfuzz.fuzzingengine.server;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class RollingSeed {
    public Seed seed;
    public List<String> validationReadResultsOracle;

    // Phase 0 observability: packet-level testID captured when this seed was
    // added to the rolling corpus. -1 if unknown (bootstrap seeds before any
    // feedback has been processed).
    public int lineageTestId = -1;

    // Phase 2 retention class. Defaults to BRANCH_BACKED so that bootstrap
    // seeds and legacy callers that do not go through the Phase 2 admission
    // path behave as they did before (never evicted, always selectable).
    // The {@link RollingSeedCorpus} overrides this at admission time based
    // on the admission reason.
    public SeedClass seedClass = SeedClass.BRANCH_BACKED;

    // Phase 2 lifecycle counters. These are maintained by
    // {@link RollingSeedCorpus} and consulted by promotion/eviction rules.
    // Using the round at which the seed was admitted (as the server's
    // finishedTestID proxy) avoids wall-clock drift across long campaigns.
    public long creationRound = -1L;
    public int timesSelectedAsParent = 0;
    public boolean hadBranchPayoff = false;
    public boolean hadStructuredCandidatePayoff = false;
    public int rediscoveryCount = 0;

    // Phase 2 independent-parent tracking. The Apr 12 master plan requires
    // rediscovery promotion to come from *independent* parents — two repeated
    // admissions from the same ancestor must count as a single rediscovery
    // event, not two. This set records every distinct parent-lineage root
    // that has contributed a rediscovery event to this seed, so
    // {@link RollingSeedCorpus#tryAdmit} can drop duplicates before bumping
    // {@link #rediscoveryCount}.
    public Set<Integer> independentRediscoveryParents = new HashSet<>();

    public RollingSeed(Seed seed, List<String> validationReadResultsOracle) {
        this.seed = seed;
        if (validationReadResultsOracle == null) {
            this.validationReadResultsOracle = new LinkedList<>();
        } else {
            this.validationReadResultsOracle = validationReadResultsOracle;
        }
    }
}
