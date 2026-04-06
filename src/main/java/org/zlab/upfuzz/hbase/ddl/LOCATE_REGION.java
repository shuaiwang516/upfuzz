package org.zlab.upfuzz.hbase.ddl;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;

public class LOCATE_REGION extends HBaseCommand {
    public LOCATE_REGION(HBaseState state) {
        super(state);

        Parameter tableName = chooseTable(state, this, null);
        this.params.add(tableName); // 0 tableName

        Parameter rowKey = chooseRowKey(state, this, null);
        this.params.add(rowKey); // 1 rowKey
    }

    @Override
    public String constructCommandString() {
        return "locate_region " + "'" + params.get(0) + "'" + ", " + "'"
                + params.get(1) + "'";
    }

    @Override
    public void updateState(State state) {
    }
}
