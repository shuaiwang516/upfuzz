package org.zlab.upfuzz.hdfs.dfs;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hdfs.HdfsState;
import org.zlab.upfuzz.utils.CONSTANTSTRINGType;

public class Expunge extends Dfs {

    public Expunge(HdfsState hdfsState) {
        super(hdfsState.subdir);

        Parameter cmd = new CONSTANTSTRINGType("-expunge")
                .generateRandomParameter(hdfsState, null);

        params.add(cmd);
    }

    @Override
    public void updateState(State state) {
    }
}
