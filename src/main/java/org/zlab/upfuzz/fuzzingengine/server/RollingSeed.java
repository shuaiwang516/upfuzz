package org.zlab.upfuzz.fuzzingengine.server;

import java.util.LinkedList;
import java.util.List;

public class RollingSeed {
    public Seed seed;
    public List<String> validationReadResultsOracle;

    // Phase 0 observability: packet-level testID captured when this seed was
    // added to the rolling corpus. -1 if unknown (bootstrap seeds before any
    // feedback has been processed).
    public int lineageTestId = -1;

    public RollingSeed(Seed seed, List<String> validationReadResultsOracle) {
        this.seed = seed;
        if (validationReadResultsOracle == null) {
            this.validationReadResultsOracle = new LinkedList<>();
        } else {
            this.validationReadResultsOracle = validationReadResultsOracle;
        }
    }
}
