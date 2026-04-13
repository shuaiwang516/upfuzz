package org.zlab.upfuzz.hbase.snapshot;

import org.zlab.upfuzz.Parameter;
import org.zlab.upfuzz.ParameterType;
import org.zlab.upfuzz.State;
import org.zlab.upfuzz.hbase.HBaseCommand;
import org.zlab.upfuzz.hbase.HBaseState;
import org.zlab.upfuzz.utils.BOOLType;

public class RESTORE_SNAPSHOT extends HBaseCommand {
    public RESTORE_SNAPSHOT(HBaseState state) {
        super(state);

        Parameter snapshotName = chooseSnapshot(state, this);
        this.params.add(snapshotName); // 0 snapshotName

        // Bool parameter
        Parameter restoreAcl = new ParameterType.OptionalType(
                new BOOLType(),
                null).generateRandomParameter(state, this, null);
        this.params.add(restoreAcl); // 1 restoreAcl
    }

    @Override
    public String constructCommandString() {
        String restoreAcl = params.get(1).toString().isEmpty() ? ""
                : String.format(", {RESTORE_ACL=>%b}",
                        params.get(1).toString());

        return "restore_snapshot " + "'" + params.get(0) + "'" + restoreAcl;
    }

    @Override
    public void updateState(State state) {
    }
}
