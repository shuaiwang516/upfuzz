package org.zlab.upfuzz.hbase.ddl;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.CONSTANTSTRINGType;

public class DROP extends HBaseCommand {
    public DROP(HBaseState state) {
        super(state);
        ParameterType.ConcreteType disabledTableNameType =
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> ((HBaseState) s).getDisabledTables(),
                        null);
        Parameter tableName = disabledTableNameType.generateRandomParameter(
                state, this);
        this.params.add(tableName); // 0 tableName
    }

    @Override
    public String constructCommandString() {
        return "drop " + "'" + params.get(0) + "'";
    }

    @Override
    public void updateState(State state) {
        ((HBaseState) state).deleteTable(params.get(0).toString());
    }
}
