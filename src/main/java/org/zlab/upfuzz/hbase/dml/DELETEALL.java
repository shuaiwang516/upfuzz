package org.zlab.upfuzz.hbase.dml;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseColumnFamily;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.CONSTANTSTRINGType;
import org.zlab.upfuzz.utils.STRINGType;
import org.zlab.upfuzz.utils.Utilities;

public class DELETEALL extends HBaseCommand {

    /**
     * Syntax:
     *
     * deleteall 'table name', 'row name'
     * // you need to specify a column to use the VISIBILITY option
     * deleteall 'table name', 'row name', 'column', {VISIBILITY => 'PRIVATE'|'SECRET'}
     *
     * instead of specifying a row, you can use a ROWPREFIXFILTER (but you CANNOT use both i.e. specify a row and use the filter)
     * deleteall 'table name', {ROWPREFIXFILTER => '...'}, 'column', {VISIBILITY => 'PRIVATE'|'SECRET'}
     *
     * if you don't wish to specify a column, use '' for the column argument
     */

    public DELETEALL(HBaseState state) {
        super(state);

        Parameter TableName = chooseTable(state, this, null);
        this.params.add(TableName); // [0] table name

        Parameter rowKey = chooseRowKey(state, this, null);
        this.params.add(rowKey); // [1] row name

        // for this command's arguments, we either use a fixed row, or we
        // use a row prefix filter, can't use both
        // so, in the constructCommandString, we check to see if the
        // parameter for the rowPrefixFilter is empty
        ParameterType.ConcreteType rowPrefixFilterType = new ParameterType.OptionalType(
                new ParameterType.NotEmpty(new STRINGType(3)),
                null);
        Parameter rowPrefixFilter = rowPrefixFilterType
                .generateRandomParameter(state, this);
        this.params.add(rowPrefixFilter); // [2] row prefix filter

        Parameter visibility = new ParameterType.OptionalType(
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> Utilities
                                .strings2Parameters(
                                        VISIBILITYTypes),
                        null),
                null).generateRandomParameter(state, this);
        params.add(visibility); // [3] visibility
    }

    @Override
    public String constructCommandString() {
        Parameter tableName = this.params.get(0);
        Parameter rowKey = this.params.get(1);
        Parameter rowPrefixFilter = this.params.get(2);
        Parameter visibility = this.params.get(3);
        StringBuilder sb = new StringBuilder();
        sb.append("deleteall " + "'").append(tableName).append("'");

//        deleteall 't1', {ROWPREFIXFILTER => 'prefix'}, 'c1', ts1, {VISIBILITY=>'PRIVATE|SECRET'}

        if (rowPrefixFilter.toString().isEmpty()) {
            sb.append(", '").append(rowKey).append("'");
            if (!visibility.toString().isEmpty()) // '' is for empty column
                                                  // specifier
                sb.append(String.format(", '', {VISIBILITY => '%s'}",
                        visibility.toString()));
        } else {
            if (visibility.toString().isEmpty())
                sb.append(String.format(", {ROWPREFIXFILTER => 'uuid%s'}",
                        rowPrefixFilter.toString()));
            else // '' is for empty column specifier
                sb.append(String.format(
                        ", {ROWPREFIXFILTER => 'uuid%s'}, '', {VISIBILITY => '%s'}",
                        rowPrefixFilter.toString(), visibility.toString()));
        }
        return sb.toString();
    }

    @Override
    public void updateState(State state) {
        // we can't perform updateState here because we don't track cells
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
