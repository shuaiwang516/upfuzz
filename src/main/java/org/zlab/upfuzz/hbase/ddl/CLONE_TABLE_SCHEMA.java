package org.zlab.upfuzz.hbase.ddl;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.CONSTANTSTRINGType;
import org.zlab.upfuzz.utils.UUIDType;
import org.zlab.upfuzz.utils.Utilities;

public class CLONE_TABLE_SCHEMA extends HBaseCommand {
    // clone_table_schema '<source_table_name>', '<new_table_name>'

    public CLONE_TABLE_SCHEMA(HBaseState state) {
        super(state);

        Parameter tableName = chooseTable(state, this, null);
        this.params.add(tableName); // [0] table name

        ParameterType.ConcreteType newTableNameType = new ParameterType.NotInCollectionType(
                new ParameterType.NotEmpty(UUIDType.instance),
                (s, c) -> ((HBaseState) s).getTables(), null);
        Parameter newTableName = newTableNameType
                .generateRandomParameter(state, this);
        this.params.add(newTableName); // [1]=newTableName

        Parameter options = new ParameterType.OptionalType(
                new ParameterType.InCollectionType(
                        CONSTANTSTRINGType.instance,
                        (s, c) -> Utilities
                                .strings2Parameters(
                                        CLONE_TABLE_SCHEMA_OPTIONAL_ARGS),
                        null),
                null).generateRandomParameter(state, this);

        this.params.add(options); // [2] = options
    }

    @Override
    public String constructCommandString() {
        // clone_table_schema '<source_table_name>', '<new_table_name>'
        // by default, clone_table_schema preserves the split keys
        // if we pass in false as the last arg, it won't
        return String.format(
                "clone_table_schema '%s', '%s'%s",
                params.get(0).toString(), params.get(1).toString(),
                params.get(2).toString());
    }

    @Override
    public void updateState(State state) {
        // Add a new table with the same schema as the source table
        Parameter newTableName = params.get(1);
        ((HBaseState) state).addTable(newTableName.toString());
        ((HBaseState) state).table2families.put(newTableName.toString(),
                ((HBaseState) state).table2families
                        .get(params.get(0).toString()));
    }

    @Override
    public void separate(State state) {
        this.params.get(1).regenerate(null, this);
    }
}
