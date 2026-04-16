package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

public class NodeFailure extends Fault {
    public int nodeIndex;

    public NodeFailure(int nodeIndex) {
        super("NodeFailure");
        this.nodeIndex = nodeIndex;
    }

    @Override
    public FaultRecover generateRecover() {
        return new NodeFailureRecover(nodeIndex);
    }

    @Override
    public String compactSignatureFragment() {
        return "n=" + nodeIndex;
    }

    @Override
    public String toString() {
        return String.format("[Fault] NodeFailure: Node[%d]", nodeIndex);
    }
}
