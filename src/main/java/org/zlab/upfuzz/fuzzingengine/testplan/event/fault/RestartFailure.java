package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

public class RestartFailure extends Fault {
    public int nodeIndex;

    public RestartFailure(int nodeIndex) {
        super("RestartFailure");
        this.nodeIndex = nodeIndex;
    }

    @Override
    public FaultRecover generateRecover() {
        return null;
    }

    @Override
    public String compactSignatureFragment() {
        return "n=" + nodeIndex;
    }

    @Override
    public String toString() {
        return String.format("[Fault] RestartFailure: Node[%d]", nodeIndex);
    }
}
