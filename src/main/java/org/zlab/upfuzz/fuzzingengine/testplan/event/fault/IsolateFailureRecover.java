package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

public class IsolateFailureRecover extends FaultRecover {
    public int nodeIndex;

    public IsolateFailureRecover(int nodeIndex) {
        super("IsolateFailureRecover");
        this.nodeIndex = nodeIndex;
    }

    @Override
    public String compactSignatureFragment() {
        return "n=" + nodeIndex;
    }

    @Override
    public String toString() {
        return String.format(
                "[FaultRecover] IsolateFailureRecover: Node[%d]",
                nodeIndex);
    }
}
