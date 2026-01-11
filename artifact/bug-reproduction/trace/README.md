# Triggering Traces

## Pre-recorded traces
During the artifact preparation, we reran the experiment for 24 hours 3 times to compute the average triggering time. We provided triggering traces (including testing logs and failure reports) for **base** and **df+vd+s** (our final mode) under current folder. All experiments are conducted using cloudlab `c220g5`.

**Results**
| Bug                                                                        | base (Time) | df+vd+s (Time) |
|----------------------------------------------------------------------------|-------------|----------------|
| [CASSANDRA-18105](https://issues.apache.org/jira/browse/CASSANDRA-18105)   |   13.58h    |     3.69h      |
| [CASSANDRA-18108](https://issues.apache.org/jira/browse/CASSANDRA-18108)   |   16.73h    |      e(6,7,8,9,10,11): (in progress)        |
| [CASSANDRA-19590](https://issues.apache.org/jira/browse/CASSANDRA-19590)   |     NA      |    11.48h      |
| [CASSANDRA-19591](https://issues.apache.org/jira/browse/CASSANDRA-19591)   |     NA      |    *Skipped    |
| [CASSANDRA-19623](https://issues.apache.org/jira/browse/CASSANDRA-19623)   |     NA      |    *Skipped    |
| [CASSANDRA-19639](https://issues.apache.org/jira/browse/CASSANDRA-19639)   |     NA      |    20.83h      |
| [CASSANDRA-19689](https://issues.apache.org/jira/browse/CASSANDRA-19689)   |     NA      |    18.64h      |
| [CASSANDRA-20182](https://issues.apache.org/jira/browse/CASSANDRA-20182)   |     NA      |    18.69h      |
| [HBASE-28583](https://issues.apache.org/jira/browse/HBASE-28583)           |     NA      |    19.36h      |
| [HBASE-28812](https://issues.apache.org/jira/browse/HBASE-28812)           |   Trivial   |    Trivial     |
| [HBASE-28815](https://issues.apache.org/jira/browse/HBASE-28815)           |   Trivial   |    Trivial     |
| [HBASE-29021](https://issues.apache.org/jira/browse/HBASE-29021)           |    0.53h    |    0.44h       |
| [HDFS-16984](https://issues.apache.org/jira/browse/HDFS-16984)             |     e0,1,2 (in progress)        |      e3,4,5 (in progress)          |
| [HDFS-17219](https://issues.apache.org/jira/browse/HDFS-17219)             |    9.60h    |    2.44h       |
| [HDFS-17686](https://issues.apache.org/jira/browse/HDFS-17686)             |    9.99h    |    2.75h       |

Available servers
* e (12 servers)
* h (6 servers) 0,1,2 3,4,5

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
cd base;
cd run1; tar -xzvf run.tar.gz; cd ..
cd run2; tar -xzvf run.tar.gz; cd ..
cd run3; tar -xzvf run.tar.gz; cd ..
cd ..

cd df_vd_s;
cd run1; tar -xzvf run.tar.gz; cd ..
cd run2; tar -xzvf run.tar.gz; cd ..
cd run3; tar -xzvf run.tar.gz; cd ..
cd ..


# CASSANDRA-19639

time1=24
time2=24
time3=24

~/project/upfuzz/bin/check_cass_19639.sh
```

## Reproduce traces from scratch

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


# CASSANDRA-18105 (dryrun: parallel num = 1)
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

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace.sh final dryrun

cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/hdfs_trace_17686.sh base dryrun
```

## Collect trace

```bash
# tar the trace
tar -czvf run.tar.gz failure server.log
```


untar
```bash
cd run1
tar -xzvf run.tar.gz
cd ..

cd run2
tar -xzvf run.tar.gz
cd ..

cd run3
tar -xzvf run.tar.gz
cd ..
```