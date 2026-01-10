# Triggering Traces

## Pre-recorded traces
We provided triggering traces for base and our final testing mode (df+vd+s). All experiments are conducted using cloudlab `c220g5`. The recorded traces are put under current folder.

Specifically, we kept (1) testing server logs and (2) bug reports for 24h.

| Bug                                                                        | base (Time) | df+vd+s (Time) |
|----------------------------------------------------------------------------|-------------|----------------|
| [CASSANDRA-18105](https://issues.apache.org/jira/browse/CASSANDRA-18105)   |   s0,1,2    |     3.69h      |
| [CASSANDRA-18108](https://issues.apache.org/jira/browse/CASSANDRA-18108)   |   e3,4,5    |    e0,1,2      |
| [CASSANDRA-19590](https://issues.apache.org/jira/browse/CASSANDRA-19590)   |     NA      |    11.48h      |
| [CASSANDRA-19591](https://issues.apache.org/jira/browse/CASSANDRA-19591)   |     NA      |    *Skipped    |
| [CASSANDRA-19623](https://issues.apache.org/jira/browse/CASSANDRA-19623)   |     NA      |    *Skipped    |
| [CASSANDRA-19639](https://issues.apache.org/jira/browse/CASSANDRA-19639)   |     NA      |    20.83h      |
| [CASSANDRA-19689](https://issues.apache.org/jira/browse/CASSANDRA-19689)   |     NA      |    18.64h      |
| [CASSANDRA-20182](https://issues.apache.org/jira/browse/CASSANDRA-20182)   |     NA      |    18.69h      |
| [HBASE-28583](https://issues.apache.org/jira/browse/HBASE-28583)           |     NA      |    e6,7,8 (TODO)      |
| [HBASE-28812](https://issues.apache.org/jira/browse/HBASE-28812)           |   Trivial   |    Trivial     |
| [HBASE-28815](https://issues.apache.org/jira/browse/HBASE-28815)           |   Trivial   |    Trivial     |
| [HBASE-29021](https://issues.apache.org/jira/browse/HBASE-29021)           |    0.53h    |    e6,7,8 (TODO)      |
| [HDFS-16984](https://issues.apache.org/jira/browse/HDFS-16984)             |             |                |
| [HDFS-17219](https://issues.apache.org/jira/browse/HDFS-17219)             |    9.60h    |     h3,4,5 (in progress)     |
| [HDFS-17686](https://issues.apache.org/jira/browse/HDFS-17686)             |             |                |


> Skipped: as described in the paper: Star (*) means the bug cannot be triggered consistently within 24 hours
and we record the shortest amount of time we observed. 
> 
> Trivial: once upgrade, the bug will be triggered

Scripts: compute the triggering time from the traces

Untar each trace, and check the average triggering time.

```bash
# TODO: add automated scripts to compute average time
# Compute the average time for bugs...

# Cassandra

# Untar 

cd ~/project/upfuzz/artifact/bug-reproduction/trace/cassandra/base/run1
tar -xzvf run.tar.gz

cd ~/project/upfuzz/artifact/bug-reproduction/trace/cassandra/base/run2
tar -xzvf run.tar.gz

cd ~/project/upfuzz/artifact/bug-reproduction/trace/cassandra/base/run3
tar -xzvf run.tar.gz

# CASSANDRA-19639

time1=24
time2=24
time3=24

~/project/upfuzz/bin/check_cass_19639.sh
```

## Reproduce traces from scratch

We also provided push-button scripts to run the entire experiment and generate the traces. To compute the triggering time, reviewers need to run the scripts for 3 times, each time for 24 hours and then check the failure triggering time. 

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
bash artifact/bug-reproduction/trace/scripts/hdfs_trace.sh base large

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace.sh final large
```

# Debug (Not for artifact reviewers)

```bash
# Clean
cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; bin/rm.sh; rm -f format_coverage.log server.log


cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3.sh final dryrun


# CASSANDRA-18105 (dryrun: parallel num = 1)
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/cass_trace_2_3_18105.sh base dryrun


# HBase
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hbase_trace.sh final dryrun

# HDFS
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace.sh final dryrun



# tar the trace
bin/clean.sh --force
tar -czvf run.tar.gz failure server.log
```