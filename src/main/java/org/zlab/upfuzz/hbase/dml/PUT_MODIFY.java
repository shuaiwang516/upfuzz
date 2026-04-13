package org.zlab.upfuzz.hbase.dml;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.CONSTANTSTRINGType;
import org.zlab.upfuzz.utils.Pair;
import org.zlab.upfuzz.utils.STRINGType;
import org.zlab.upfuzz.utils.Utilities;

import java.util.ArrayList;
import java.util.List;

public class PUT_MODIFY extends HBaseCommand {

    public PUT_MODIFY(HBaseState state) {
        super(state);

        Parameter tableName = chooseTable(state, this, null);
        this.params.add(tableName); // [0] table name

        Parameter columnFamilyName = chooseNotEmptyColumnFamily(state, this,
                null);
        this.params.add(columnFamilyName); // [1] column family name

        // FIXME: If there's no row key in current cf, this will fail
        // with exception.
        Parameter rowKey = chooseRowKey(state, this, null);
        this.params.add(rowKey); // [2] row name

        Parameter column = chooseColumnName(state, this,
                columnFamilyName.toString(), null);
        this.params.add(column); // [3] column2type

        ParameterType.ConcreteType valueType = new ParameterType.NotEmpty(
                new STRINGType(20));
        Parameter value = valueType.generateRandomParameter(state, this);
        this.params.add(value); // [4] value

        Parameter VISIBILITYType = new ParameterType.OptionalType(
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> Utilities
                                .strings2Parameters(
                                        VISIBILITYTypes),
                        null),
                null)
                        .generateRandomParameter(state, this);
        this.params.add(VISIBILITYType); // [5] visibility
    }

    @Override
    public String constructCommandString() {
        Parameter tableName = params.get(0);
        Parameter columnFamilyName = params.get(1);
        Parameter rowKey = params.get(2);
        Parameter columnName = params.get(3);
        String colNameStr = columnName.toString();
//        colNameStr = colNameStr.substring(0, colNameStr.indexOf(" "));
        Parameter insertValues = params.get(4);
        String valueStr = insertValues.toString();
        String visibilityStr = params.get(5).toString();
        if (!visibilityStr.isEmpty()) {
            visibilityStr = ", {VISIBILITY=>'" + visibilityStr + "'}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("put ")
                .append("'").append(tableName.toString()).append("', ")
                .append("'").append(rowKey.toString()).append("', ")
                .append("'").append(columnFamilyName.toString()).append(":")
                .append(colNameStr).append("', ")
                .append("'").append(valueStr).append("'")
                .append(visibilityStr);
        return sb.toString();
    }

    @Override
    public void updateState(State state) {
    }

    @Override
    public boolean mutate(State s) throws Exception {
        try {
            super.mutate(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
