package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

public class NodeFailureRecover extends FaultRecover {
    public int nodeIndex;

    public NodeFailureRecover(int nodeIndex) {
        super("NodeFailureRecover");
        this.nodeIndex = nodeIndex;
    }

    @Override
    public String compactSignatureFragment() {
        return "n=" + nodeIndex;
    }

    @Override
    public String toString() {
        return String.format(
                "[FaultRecover] NodeFailure Recover: Node[%d]",
                nodeIndex);
    }

}
