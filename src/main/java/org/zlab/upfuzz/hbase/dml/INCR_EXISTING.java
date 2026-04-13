package org.zlab.upfuzz.hbase.dml;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.*;

import java.util.HashMap;

public class INCR_EXISTING extends HBaseCommand {
    // Syntax: put '<table_name>', '<row_key>', '<column_family:qualifier>',
    // '<value>', [timestamp]

    // New row, column
    public INCR_EXISTING(HBaseState state) {
        super(state);

        Parameter tableName = chooseTable(state, this, null);
        this.params.add(tableName); // [0] table name

        Parameter columnFamilyName = chooseColumnFamily(state, this, null);
        this.params.add(columnFamilyName); // [1] column family name

        Parameter rowKey = chooseRowKey(state, this, null);
        this.params.add(rowKey); // [2] row key

        Parameter column = chooseColumnName(state, this,
                columnFamilyName.toString(), null);
        this.params.add(column); // [3] column2type

        Parameter incrValue = new ParameterType.OptionalType(
                new INTType(1, 100), null).generateRandomParameter(state,
                        this);
        this.params.add(incrValue); // [4] column family name
    }

    @Override
    public String constructCommandString() {
        Parameter tableName = params.get(0);
        Parameter columnFamilyName = params.get(1);
        Parameter rowKey = params.get(2);
        Parameter columnName = params.get(3);
        Parameter incrValue = params.get(4);
        String colNameStr = columnName.toString();
//        int spaceIdx = colNameStr.indexOf(" ");
//        if (spaceIdx != -1)
//            colNameStr = colNameStr.substring(0, spaceIdx);
        String valueStr;
        if (incrValue.toString().isEmpty()) {
            valueStr = "";
        } else {
            valueStr = ", " + incrValue.toString();
        }
        return "incr "
                + "'" + tableName.toString() + "', "
                + "'" + rowKey.toString() + "', "
                + "'" + columnFamilyName.toString() + ":"
                + colNameStr + "'"
                + valueStr;
    }

    @Override
    public void updateState(State state) {
        Parameter tableName = params.get(0);
        Parameter columnFamilyName = params.get(1);
        Parameter rowKey = params.get(2);
        Parameter col2Type = params.get(3);
        // just like we call addRowKey, the column, if not previously present in
        // the column family, will be added

        HBaseState s = ((HBaseState) state);

        // checks because the parameters can get mutated
        if (!s.table2families.containsKey(tableName.toString())) {
            return;
        }
        if (s.table2families.get(tableName.toString()) == null) {
            s.table2families.put(tableName.toString(), new HashMap<>());
        }
        if (!s.table2families.get(tableName.toString())
                .containsKey(columnFamilyName.toString())) {
            return;
        }

        // we call addRowKey to add a row key because
        // incr 't1', 'r3', 'cf2' will also create a new row 'r3' in column
        // family 'cf2' if 'r3' previously didn't exist
        s.addRowKey(tableName.toString(), rowKey.toString());

        s.table2families.get(tableName.toString())
                .get(columnFamilyName.toString())
                .addColName2Type(col2Type);
    }

//    @Override
//    public boolean isValid(State state) {
//        if (!validConstruction) {
//            return false;
//        }
//        Parameter tableName = params.get(0);
//        Parameter columnFamilyName = params.get(1);
//
//        HBaseState s = ((HBaseState) state);
//
//        // checks because the parameters can get mutated
//        if (!s.table2families.containsKey(tableName.toString())) {
//            return false;
//        }
//        if (!s.table2families.get(tableName.toString())
//                .containsKey(columnFamilyName.toString())) {
//            return false;
//        }
//        return true;
//    }

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
