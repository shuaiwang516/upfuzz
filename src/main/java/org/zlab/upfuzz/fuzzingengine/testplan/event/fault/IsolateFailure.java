package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

public class IsolateFailure extends Fault {
    public int nodeIndex;

    public IsolateFailure(int nodeIndex) {
        super("IsolateFailure");
        this.nodeIndex = nodeIndex;
    }

    @Override
    public FaultRecover generateRecover() {
        return new IsolateFailureRecover(nodeIndex);
    }

    @Override
    public String compactSignatureFragment() {
        return "n=" + nodeIndex;
    }

    @Override
    public String toString() {
        return String.format("[Fault] Isolate Node[%d]", nodeIndex);
    }
}
