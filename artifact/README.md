# Artifact of upfuzz

This is the source code repo for **UpFuzz: Detecting Data Format Incompatibility Bugs during Distributed Storage System Upgrade** (NSDI 2026). 

### Overview

The instructions will reproduce the key results in Figure 14, Table 2, and Table 4. That is, the following instructions will lead you to (1) reproduce the bugs found by upfuzz and (2) generate the bug-triggering test plan.

The entire artifact evaluation process can take about 3 hours.

1. [Kick-the-tires Instructions](#kick-the-tires-instructions-30-mins)
2. [Full Evaluation Instructions](#full-evaluation-instructions-25h)


## Experiment Data

To evaluate upfuzz, we conducted a large number of experiments, totaling > *five months* of a single machine time (We paralleled experiments with more servers). In accordance with the artifact evaluation (AE) guidelines, we do not expect reviewers to rerun all experiments from scratch to validate our results.

For experiments that require substantial computational resources, we provide:
- Pre-built instrumented binaries
- Experiment traces

> Note: if reviewers want to build the instrumented binaries from scratch, check out [ae-scratch.md](ae-scratch.md).

## Requirements

We strongly encourage you to run experiments using cloudlab machines, specifically wisconsin's `c220g5`. All our experiments were conducted using cloudlab `c220g5`. The following scripts are tested from a bare-metal `c220g5` machine.

Start up an instance for wisconsin's `c220g5` with **Ubuntu 22.04** or directly use the cloudlab profile: [upfuzz-ae](https://www.cloudlab.us/p/sosp21-upgrade/upfuzz-ae). This profile starts up one node of wisconsin's `c220g5`. 

Run the following script to install the required dependencies.

```bash
wget https://raw.githubusercontent.com/zlab-purdue/upfuzz/main/artifact/setup-upfuzz-env.sh
bash setup-upfuzz-env.sh
```

The above scripts mount the SSD to `/mnt_ssd` and the HDD to `/mydata`. upfuzz uses SSD for temporary storage for testing clusters and HDD for persistent storage for recorded tests and failure reports.

```bash
node0:~ $ lsblk
NAME    MAJ:MIN RM   SIZE RO TYPE MOUNTPOINTS
sda       8:0    0 447.1G  0 disk 
├─sda1    8:1    0   256M  0 part /boot/efi
├─sda2    8:2    0     1M  0 part 
├─sda3    8:3    0    64G  0 part /
├─sda4    8:4    0 374.9G  0 part /mnt_ssd
└─sda99 259:0    0     8G  0 part [SWAP]
sdb       8:16   0   1.1T  0 disk /mydata

node0:~ $ df -h
Filesystem                                     Size  Used Avail Use% Mounted on
tmpfs                                           19G  1.9M   19G   1% /run
/dev/sda3                                       63G  4.8G   55G   9% /
...
/dev/sda4                                      368G  212K  350G   1% /mnt_ssd
/dev/sdb                                       1.1T   36K  1.1T   1% /mydata
```

## Kick-the-tires Instructions (~30 mins)

The previous steps make sure there exists `~/project/` folder in HDD. 

> Note that SSD/HDD are not necessary, you could use any other devices to store the upfuzz and you only need to change the `cd` path to `cd /PATH/TO/upfuzz` in the following scripts. The `~/project/` here is only for push-button evaluation convenience.

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
bin/clean.sh --force
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
bin/clean.sh --force
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
bin/clean.sh --force
# remove generated files (recorded tests, logs, failure reports...)
bin/rm.sh
```

## Full Evaluation Instructions (2.5h)

To facilitate a push-button artifact evaluation workflow, we provide pre-built instrumented binaries for all system versions evaluated in the paper. Using these binaries, reviewers do not need to re-run source code analysis or instrumentation.

We provide scripts and traces to reproduce all reported results.

Each bug reproduction script is independent of each other, you could run them one by one or in parallel with multiple servers.

### Reproduce Figure 14: State Exploration (~10 mins)

Scripts are provided to generate the figure from pre-recorded testing traces.

```bash
cd ~/project/upfuzz
bash artifact/state-exploration/reproduce_state_exploration.sh
```

State exploration figure is generated at `artifact/state-exploration/state-exploration-data/all.pdf`


### Reproduce Table 2: Triggering New Bugs (110 mins)

#### Triggering time (~10 mins)

We provided the triggering time along with recorded traces (including testing logs and failure reports) for two modes: **base** and **df+vd+s** (our final mode)in [bug-reproduction/trace/README.md](bug-reproduction/trace/README.md).

#### Reproducing all bugs (~100 mins)

In this mode, upfuzz runs directly using **pre-generated command sequences** to reproduce each bug individually. Reviews could simply run the reproducing mode and observe the triggering results.

The bug reports are saved in `upfuzz/failure` folder.

We provided each bug an independent script to reproduce for better investigation. 

> The link of the bug links to the JIRA issue page, where you could view the bug description and check the status of the bug: **Reported**, **Confirmed**, or **Fixed**.
> 
> * **Reported**: the bug is reported, but not acknowledged as a bug yet by the developers.
> 
> * **Confirmed**: the bug is acknowledged by the developers in the JIRA ticket. Developers might confirm the bug in the comment (e.g. [HBASE-28812](https://issues.apache.org/jira/browse/HBASE-28812)). 
>   * Cassandra developers might directly change the ticket status from "Triage Needed" to "OPEN" without adding comment, we have one bug confirmed this way: [CASSANDRA-19623](https://issues.apache.org/jira/browse/CASSANDRA-19623). You could click the jira ticket activity *History* button to view the ticket status change.
>
> * **Fixed**: the fix is merged into the main branch.

1. [CASSANDRA-18105](https://issues.apache.org/jira/browse/CASSANDRA-18105) (Fixed)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 18105 false
```

2. [CASSANDRA-18108](https://issues.apache.org/jira/browse/CASSANDRA-18108) (Confirmed)
```bash
# 4.1.6 => 5.0.2
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_4_5.sh 18108 false
```

3. [CASSANDRA-19590](https://issues.apache.org/jira/browse/CASSANDRA-19590) (Confirmed)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19590 false
```

4. [CASSANDRA-19591](https://issues.apache.org/jira/browse/CASSANDRA-19591) (Reported)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19591 false
```

5. [CASSANDRA-19623](https://issues.apache.org/jira/browse/CASSANDRA-19623) (Confirmed)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19623 true
```

6. [CASSANDRA-19639](https://issues.apache.org/jira/browse/CASSANDRA-19639) (Reported)
> This bug might require running the script multiple times to trigger. Upfuzz's bug reproduction mechansim is able to trigger it 5 out of 6 times with the same command sequence.

```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19639 true
```

7. [CASSANDRA-19689](https://issues.apache.org/jira/browse/CASSANDRA-19689) (Reported)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 19689 true
```

8. [CASSANDRA-20182](https://issues.apache.org/jira/browse/CASSANDRA-20182) (Reported)
```bash
# 2.2.19 => 3.0.30
cd ~/project/upfuzz
bash artifact/bug-reproduction/cass_repo_2_3.sh 20182 false
```

9. [HBASE-28583](https://issues.apache.org/jira/browse/HBASE-28583) (Reported)
```bash
# 2.5.9 => 3.0.0 (516c89e8597fb6)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo.sh 28583 false
```

10. [HBASE-28812](https://issues.apache.org/jira/browse/HBASE-28812) (Fixed)
```bash
# 2.6.0 => 3.0.0 (a030e8099840e64)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo_28812.sh 28812 false
```

11. [HBASE-28815](https://issues.apache.org/jira/browse/HBASE-28815) (Confirmed)
```bash
# 1.7.2 => 2.6.0
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo_28815.sh 28815 false
```

12. [HBASE-29021](https://issues.apache.org/jira/browse/HBASE-29021) (Fixed)
```bash
# 2.5.9 => 3.0.0 (516c89e8597fb6)
cd ~/project/upfuzz
bash artifact/bug-reproduction/hbase_repo.sh 29021 false
```

13. [HDFS-16984](https://issues.apache.org/jira/browse/HDFS-16984) (Confirmed)
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
bash artifact/bug-reproduction/bugs/HDFS-16984/repo.sh
```
14. [HDFS-17219](https://issues.apache.org/jira/browse/HDFS-17219) (Reported)
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
bash artifact/bug-reproduction/hdfs_repo.sh 17219 false
```

15. [HDFS-17686](https://issues.apache.org/jira/browse/HDFS-17686) (Reported)
> This bug might require running the script multiple times to trigger. Upfuzz's bug reproduction mechansim is able to trigger it 5 out of 6 times with the same command sequence.
```bash
# 2.10.2 => 3.3.6
cd ~/project/upfuzz
bash artifact/bug-reproduction/bugs/HDFS-17686/repo.sh
```

### Reproduce Table 4: Overhead (~30 mins)

The script will run upfuzz with/without collecting data format feedback and then print out the overhead. 

> Note: overhead could be flucating due to the randomness of the system. The scripts would run for once, but results in our paper are the average of 3 runs.

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