package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

public class LinkFailure extends Fault {
    public int nodeIndex1;
    public int nodeIndex2;

    public LinkFailure(int nodeIndex1, int nodeIndex2) {
        super("LinkFailure");
        this.nodeIndex1 = nodeIndex1;
        this.nodeIndex2 = nodeIndex2;
    }

    @Override
    public FaultRecover generateRecover() {
        return new LinkFailureRecover(nodeIndex1, nodeIndex2);
    }

    @Override
    public String compactSignatureFragment() {
        // Order-insensitive so LinkFailure(1,2) and LinkFailure(2,1)
        // share the same skeleton.
        int lo = Math.min(nodeIndex1, nodeIndex2);
        int hi = Math.max(nodeIndex1, nodeIndex2);
        return "n1=" + lo + ",n2=" + hi;
    }

    @Override
    public String toString() {
        return String.format("[Fault] LinkFailure: Node[%d], Node[%d]",
                nodeIndex1, nodeIndex2);
    }
}
