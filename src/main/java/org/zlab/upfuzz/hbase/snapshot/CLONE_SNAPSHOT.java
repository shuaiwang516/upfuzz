package org.zlab.upfuzz.hbase.snapshot;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;

public class CLONE_SNAPSHOT extends HBaseCommand {

    public CLONE_SNAPSHOT(HBaseState state) {
        super(state);

        Parameter snapshotName = chooseSnapshot(state, this);
        this.params.add(snapshotName); // 0 snapshotName
        Parameter tableName = chooseNewTable(state, this);
        this.params.add(tableName); // 1 tableName
    }

    @Override
    public String constructCommandString() {
        // clone_snapshot 'snapshot_name', 'new_table_name'
        return "clone_snapshot " + "'" + params.get(0) + "'" + ", " + "'"
                + params.get(1) + "'";
    }

    @Override
    public void updateState(State state) {
        String snapshotName = params.get(0).toString();
        String newTableName = params.get(1).toString();
        ((HBaseState) state).addTable(newTableName,
                ((HBaseState) state).snapshots.get(snapshotName));
    }
}
