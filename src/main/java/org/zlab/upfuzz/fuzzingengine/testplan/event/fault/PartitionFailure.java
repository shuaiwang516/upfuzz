package org.zlab.upfuzz.fuzzingengine.testplan.event.fault;

import java.util.Set;
import java.util.TreeSet;

public class PartitionFailure extends Fault {
    public Set<Integer> nodeSet1;
    public Set<Integer> nodeSet2;

    public PartitionFailure(Set<Integer> nodeSet1, Set<Integer> nodeSet2) {
        super("PartitionFailure");
        this.nodeSet1 = nodeSet1;
        this.nodeSet2 = nodeSet2;
    }

    @Override
    public FaultRecover generateRecover() {
        return new PartitionFailureRecover(nodeSet1, nodeSet2);
    }

    @Override
    public String compactSignatureFragment() {
        // Order-insensitive: sort both sets, then sort the pair of
        // sorted sets so PartitionFailure({1,2},{3}) and
        // PartitionFailure({3},{1,2}) produce the same fragment.
        String a = nodeSet1 == null
                ? "[]"
                : new TreeSet<>(nodeSet1).toString();
        String b = nodeSet2 == null
                ? "[]"
                : new TreeSet<>(nodeSet2).toString();
        if (a.compareTo(b) > 0) {
            String tmp = a;
            a = b;
            b = tmp;
        }
        return "p1=" + a + ",p2=" + b;
    }
}
