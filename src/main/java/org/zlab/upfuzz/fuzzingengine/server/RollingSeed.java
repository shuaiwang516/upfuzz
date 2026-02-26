package org.zlab.upfuzz.fuzzingengine.server;

import java.util.LinkedList;
import java.util.List;

public class RollingSeed {
    public Seed seed;
    public List<String> validationReadResultsOracle;

    public RollingSeed(Seed seed, List<String> validationReadResultsOracle) {
        this.seed = seed;
        if (validationReadResultsOracle == null) {
            this.validationReadResultsOracle = new LinkedList<>();
        } else {
            this.validationReadResultsOracle = validationReadResultsOracle;
        }
    }
}
