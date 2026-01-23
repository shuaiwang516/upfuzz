# Generate Traces From Scratch (24h\*6\*11=1584h=66 machine days)

You could also run the entire experiment and generate the traces. We provided push-button scripts to run the entire experiment and generate the traces. To compute the triggering time, you need to run the scripts for 3 times, each time for 24 hours and then check the failure triggering time. 

Note that this would need a large amount of computational resources.

### Cassandra
Parallel num = 30
```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3.sh base large

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3.sh final large

# CASSANDRA-18105 base
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3_18105.sh base large

# CASSANDRA-18105 final
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3_18105.sh final large

# CASSANDRA-18108 base
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_18108.sh base large

# CASSANDRA-18108 final
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_18108.sh final large
```

### HBase

Parallel num = 12

```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hbase_trace.sh base large

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hbase_trace.sh final large
```

### HDFS

Parallel num = 12

```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_16984.sh base large

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_16984.sh final large

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_17219.sh base large

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_17219.sh final large


cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_17686.sh base large

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_17686.sh final large
```

# Debug

## Clean

```bash
cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; bin/rm.sh; rm -f format_coverage.log server.log
git checkout .
git pull
```

## Dry Run

Parallel num = 1, only to check the testing workflow works correctly.

```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3.sh final dryrun

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3_18105.sh base dryrun


# HBase
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hbase_trace.sh final dryrun

# HDFS
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_16984.sh base dryrun

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_16984.sh final dryrun
```

## Clean trace
```bash
# mac
find . -type f -name 'run.tar.gz' -delete
tar -czvf recorded_traces.tar.gz recorded_traces
```

## Collect trace

```bash
# tar the trace
bin/clean.sh --force
tar -czvf run.tar.gz failure server.log
```