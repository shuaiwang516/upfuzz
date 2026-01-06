# Artifact of upfuzz

## Overview

This repository contains the source code and artifact materials for **UpFuzz**, a framework for discovering data-format and upgrade bugs in distributed storage systems.

We provided our source code, the push-button script to run the testing framework and our experiment trace for evaluation. 

## Experiment data

To evaluate upfuzz, we conducted a large number of experiments, totaling > five months of a single machine time (We paralleled experiments with more servers). In accordance with the artifact evaluation (AE) guidelines, we do not expect reviewers to rerun all experiments from scratch to validate our results.

Instead, we release our **raw experimental data**, allowing reviewers to download the data and run scripts to reproduce all figures and tables reported in the paper.

For experiments that require substantial computational resources, we provide:
- Pre-built instrumented binaries
- Experiment traces

These artifacts enable result reproduction without re-running the full-scale experiments.

---
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

TODO: add push-button script for instrumented testing

### Test Cassandra: 3.11.17 => 4.1.4

**Start up testing process**
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

**Stop Testing and Clean Up**
```bash
# stop fuzzing server, client, and containers
bin/clean.sh
# remove generated files
# Testing could generate lots of files (recorded tests, logs, failure reports...)
bin/rm.sh
```

### Test HBase: 2.4.18 => 2.5.9

```bash
cd ~/project/upfuzz
bash artifact/test-hbase.sh
```

**Check Testing Status**

same as Cassandra

**Stop Testing and Clean Up**

same as Cassandra

### Test HDFS: 2.10.2 => 3.3.6

```bash
cd ~/project/upfuzz
bash artifact/test-hdfs.sh
```

**Check Testing Status**

same as Cassandra

**Stop Testing and Clean Up**

same as Cassandra

## Full Evaluation Instructions

To facilitate a push-button artifact evaluation workflow, we provide pre-built instrumented binaries for all system versions evaluated in the paper. Using these binaries, reviewers do not need to re-run source code analysis or instrumentation.

We provide scripts and traces to reproduce all reported results.

### Reproduce Figure 14: State Exploration
Run UpFuzz in state exploration mode.

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

### Reproduce Table 2: trigger newly detected bugs 

In this mode, UpFuzz runs directly using pre-generated command sequences to reproduce each bug individually. Reviews could simply run the reproducing mode and observe the triggering results.

Each bug reproduction contains a reproduction script. As long as upfuzz repo is cloned, use the push-button script could reproduce the bug immediately (each bug takes < 5 minutes). The bug reports is under `upfuzz/failure` folder.

```bash
upfuzz (main*) $ ls failure 
failure_0
```

1. CASSANDRA-18105 (Tested)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 18105 false
```

2. CASSANDRA-18108 (Tested)
```bash
# 4.1.6 => 5.0.2
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_4_5.sh 18108 false
```

3. CASSANDRA-19590 (Tested)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19590 false
```

4. CASSANDRA-19591 (Tested)
```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19591 false
```

5. CASSANDRA-19623 (Tested)
```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19623 true
```

6. CASSANDRA-19639 (Tested)
```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19639 true
```

7. CASSANDRA-19689 (Tested)
```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19689 true
```

8. CASSANDRA-20182 (Tested)
```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 20182 false
```

9. HBASE-28583 (Tested)
```bash
# 2.5.9 => 3.0.0 (516c89e8597fb6)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo.sh 28583 false
```

10. HBASE-28812 (s2)
```bash
# 2.6.0 => 3.0.0 (a030e8099840e640684a68b6e4a79e7c1d5a6823)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo_28812.sh 28812 false
```

11. HBASE-28815 (s1)
```bash
# 1.7.2 => 2.6.0
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo_28815.sh 28815 false
```

12. HBASE-29021 (Tested)
```bash
# 2.5.9 => 3.0.0 (516c89e8597fb6)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo.sh 29021 false
```

13. HDFS-16984 (Tested)
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
mkdir -p /tmp/upfuzz/hdfs
cp -r artifact/bug-reproduction/bugs/HDFS-16984/GUBIkxOc /tmp/upfuzz/hdfs/
bash artifact/bug-reproduction/hdfs_repo.sh 16984 false
```
14.HDFS-17219 (Tested)
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
bash artifact/bug-reproduction/hdfs_repo.sh 17219 false
```

15.HDFS-17686 (Tested)
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
mkdir -p /tmp/upfuzz/hdfs
cp -r artifact/bug-reproduction/bugs/HDFS-17686/pVfezjff /tmp/upfuzz/hdfs/
bash artifact/bug-reproduction/hdfs_repo.sh 17686 false
```

### Reproduce Table 2: triggering trace

We'll provide the detailed testing logs for the following 2 modes:
* Baseline (BC) mode 
* Final mode (BC + DF + VD)

### Reproduce Table 4: Overhead
Scripts are provided to reproduce the overhead measurements reported in the paper.

* Download our prebuilt instrumented binaries, run upfuzz with a test with/without collecting data format feedback and then compute the overhead.

We'll provide a push-button script to get this result directly.