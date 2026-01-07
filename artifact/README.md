# Artifact of upfuzz

## Overview

This repository contains the source code and artifact materials for **upfuzz**, a framework for discovering data-format and upgrade bugs in distributed storage systems.

We provided our source code, the push-button script to run the testing framework and our experiment traces for evaluation.

## Experiment Data

To evaluate upfuzz, we conducted a large number of experiments, totaling > five months of a single machine time (We paralleled experiments with more servers). In accordance with the artifact evaluation (AE) guidelines, we do not expect reviewers to rerun all experiments from scratch to validate our results.

> Note: if reviewers want to build the instrumented binaries from scratch, check out [ae-scratch.md](ae-scratch.md).

For experiments that require substantial computational resources, we provide:
- Pre-built instrumented binaries
- Experiment traces

These artifacts enable result reproduction without re-running the full-scale experiments.

## Requirements

We strongly encourage you to run experiments using cloudlab machines, specifically `c220g5`. All our experiments were conducted using cloudlab `c220g5`.

Start up an instance for `c220g5`, run the following scripts to install all required dependencies.

```bash
wget https://raw.githubusercontent.com/zlab-purdue/upfuzz/main/artifact/setup-upfuzz-env.sh
bash setup-upfuzz-env.sh
```

## Kick-the-tires Instructions (~30 minutes)

The previous steps make sure there exists `~/project/` folder.

This section demonstrates how to start upgrade testing immediately for Cassandra, HBase and HDFS.

### Clone the repo
```bash
cd ~/project
git clone https://github.com/zlab-purdue/upfuzz.git

cd ~/project/upfuzz
```

### Test Cassandra: 3.11.17 => 4.1.4

**Start Testing**
* It starts up one fuzzing server and one fuzzing client (parallel num = 1)

```bash
cd ~/project/upfuzz
bash artifact/test-cass.sh
```

**Check Testing Status**
```bash
cd ~/project/upfuzz
# check logs

# server logs: contain testing coverage, failure info
view server.log
view client.log

# check containers: you would see 1 container running
docker ps

# check failure (if any bug is detected)
ls failure/
```

Example server.log
```
-------------------------------------------------- TestStatus ---------------------------------------------------------------
System: cassandra
Upgrade Testing: apache-cassandra-2.2.19=>apache-cassandra-3.0.30
=============================================================================================================================
|              cur testID : 295|              total exec : 234|           skipped upgrade : 0|                              |
|               run time : 889s|                     round : 7|          ori BC : 20460/49900|   up BC upgrade : 24893/63031|
|                QueueType : BC|               queue size : 67|                     index : 6|                              |
-----------------------------------------------------------------------------------------------------------------------------
|            fullstop crash : 0|               event crash : 0|             inconsistency : 0|                 error log : 0|
-----------------------------------------------------------------------------------------------------------------------------
```

**Stop Testing and Clean Up**
```bash
# stop fuzzing server, client, and containers
bin/clean.sh
# remove generated files (recorded tests, logs, failure reports...)
bin/rm.sh
```

### Test HBase: 2.4.18 => 2.5.9

**Start Testing**
```bash
cd ~/project/upfuzz
bash artifact/test-hbase.sh
```

**Check Testing Status**
```bash
cd ~/project/upfuzz
# check logs

# server logs: contain testing coverage, failure info
view server.log
view client.log

# check containers: you would see 1 container running
docker ps

# check failure (if any bug is detected)
ls failure/
```

Example server.log
```
-------------------------------------------------- TestStatus ---------------------------------------------------------------
System: hbase
Upgrade Testing: hbase-2.5.9=>hbase-3.0.0
=============================================================================================================================
|                cur testID : 1|                total exec : 1|           skipped upgrade : 0|                              |
|               run time : 187s|                     round : 0|         ori BC : 31483/123458|  up BC upgrade : 40539/137340|
|                QueueType : BC|                queue size : 1|                     index : 0|                              |
-----------------------------------------------------------------------------------------------------------------------------
|            fullstop crash : 0|               event crash : 0|             inconsistency : 0|                 error log : 0|
-----------------------------------------------------------------------------------------------------------------------------
```

**Stop Testing and Clean Up**
```bash
# stop fuzzing server, client, and containers
bin/clean.sh
# remove generated files (recorded tests, logs, failure reports...)
bin/rm.sh
```

### Test HDFS: 2.10.2 => 3.3.6

**Start Testing**
```bash
cd ~/project/upfuzz
bash artifact/test-hdfs.sh
```

**Check Testing Status**
```bash
cd ~/project/upfuzz
# check logs

# server logs: contain testing coverage, failure info
view server.log
view client.log

# check containers: you would see 1 container running
docker ps

# check failure (if any bug is detected)
ls failure/
```

**Stop Testing and Clean Up**
```bash
# stop fuzzing server, client, and containers
bin/clean.sh
# remove generated files (recorded tests, logs, failure reports...)
bin/rm.sh
```

## Full Evaluation Instructions

To facilitate a push-button artifact evaluation workflow, we provide pre-built instrumented binaries for all system versions evaluated in the paper. Using these binaries, reviewers do not need to re-run source code analysis or instrumentation.

We provide scripts and traces to reproduce all reported results.

### Reproduce Figure 14: State Exploration
Run upfuzz in state exploration mode.

Scripts are provided to generate the final figure.

```bash
cd ~/project/upfuzz
cd artifact/state-exploration
wget https://github.com/zlab-purdue/upfuzz/releases/download/v1.0-data/state-exploration-data.tar.gz
tar -xzvf state-exploration-data.tar.gz
python3 run.py

# all.pdf will be generated at path artifact/state-exploration/all.pdf
ls -l all.pdf
```

### Reproduce Table 2: Triggering New Bugs 

In this mode, upfuzz runs directly using pre-generated command sequences to reproduce each bug individually. Reviews could simply run the reproducing mode and observe the triggering results.

Each bug reproduction contains a reproduction script. As long as upfuzz repo is cloned, use the push-button script could reproduce the bug immediately (each bug takes < 5 minutes). The bug reports is under `upfuzz/failure` folder.

```bash
upfuzz (main*) $ ls failure 
failure_0
```

1. [CASSANDRA-18105](https://issues.apache.org/jira/browse/CASSANDRA-18105)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 18105 false
```

2. [CASSANDRA-18108](https://issues.apache.org/jira/browse/CASSANDRA-18108)
```bash
# 4.1.6 => 5.0.2
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_4_5.sh 18108 false
```

3. [CASSANDRA-19590](https://issues.apache.org/jira/browse/CASSANDRA-19590)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19590 false
```

4. [CASSANDRA-19591](https://issues.apache.org/jira/browse/CASSANDRA-19591)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19591 false
```

5. [CASSANDRA-19623](https://issues.apache.org/jira/browse/CASSANDRA-19623)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19623 true
```

6. [CASSANDRA-19639](https://issues.apache.org/jira/browse/CASSANDRA-19639)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19639 true
```

7. [CASSANDRA-19689](https://issues.apache.org/jira/browse/CASSANDRA-19689)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19689 true
```

8. [CASSANDRA-20182](https://issues.apache.org/jira/browse/CASSANDRA-20182)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 20182 false
```

9. [HBASE-28583](https://issues.apache.org/jira/browse/HBASE-28583)
```bash
# 2.5.9 => 3.0.0 (516c89e8597fb6)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo.sh 28583 false
```

10. [HBASE-28812](https://issues.apache.org/jira/browse/HBASE-28812)
```bash
# 2.6.0 => 3.0.0 (a030e8099840e64)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo_28812.sh 28812 false
```

11. [HBASE-28815](https://issues.apache.org/jira/browse/HBASE-28815)
```bash
# 1.7.2 => 2.6.0
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo_28815.sh 28815 false
```

12. [HBASE-29021](https://issues.apache.org/jira/browse/HBASE-29021)
```bash
# 2.5.9 => 3.0.0 (516c89e8597fb6)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo.sh 29021 false
```

13. [HDFS-16984](https://issues.apache.org/jira/browse/HDFS-16984)
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
bash artifact/bug-reproduction/bugs/HDFS-16984/repo.sh
```
14. [HDFS-17219](https://issues.apache.org/jira/browse/HDFS-17219)
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
bash artifact/bug-reproduction/hdfs_repo.sh 17219 false
```

15. [HDFS-17686](https://issues.apache.org/jira/browse/HDFS-17686)
> Note: this bug might require running the script multiple times to trigger
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
bash artifact/bug-reproduction/bugs/HDFS-17686/repo.sh
```

<!-- ### Reproduce Table 2: triggering trace

We'll provide the detailed testing logs for the following 2 modes:
* Baseline (BC) mode 
* Final mode (BC + DF + VD) -->

### Reproduce Table 4: Overhead

The script will run upfuzz with/without collecting data format feedback and then print out the overhead.

Cassandra-2.2.19
```bash
cd ~/project/upfuzz
bash artifact/overhead/cassandra/cass_overhead_2.sh
```

Cassandra-4.1.6
```bash
cd ~/project/upfuzz
bash artifact/overhead/cassandra/cass_overhead_4.sh
```

HBase-2.5.9
```bash
cd ~/project/upfuzz
bash artifact/overhead/hbase/hbase_overhead.sh
```

HDFS-2.10.2
```bash
cd ~/project/upfuzz
bash artifact/overhead/hdfs/hdfs_overhead.sh
```