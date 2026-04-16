package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

public class LinkFailureRecover extends FaultRecover {
    public int nodeIndex1;
    public int nodeIndex2;

    public LinkFailureRecover(int nodeIndex1, int nodeIndex2) {
        super("LinkFailureRecover");
        this.nodeIndex1 = nodeIndex1;
        this.nodeIndex2 = nodeIndex2;
    }

    @Override
    public String compactSignatureFragment() {
        int lo = Math.min(nodeIndex1, nodeIndex2);
        int hi = Math.max(nodeIndex1, nodeIndex2);
        return "n1=" + lo + ",n2=" + hi;
    }

    @Override
    public String toString() {
        return String.format(
                "[FaultRecover] LinkFailure Recover: Node[%d], Node[%d]",
                nodeIndex1, nodeIndex2);
    }
}
