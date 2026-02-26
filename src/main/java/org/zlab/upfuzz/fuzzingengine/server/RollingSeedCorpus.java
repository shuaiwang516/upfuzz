package org.zlab.upfuzz.fuzzingengine.server;

public class RollingSeedCorpus {
    CycleQueue<RollingSeed> cycleQueue = new CycleQueue<>();

    public RollingSeed getSeed() {
        return cycleQueue.getNextSeed();
    }

    public void addSeed(RollingSeed seed) {
        cycleQueue.addSeed(seed);
    }

    public boolean isEmpty() {
        return cycleQueue.isEmpty();
    }

    public int size() {
        return cycleQueue.size();
    }
}
