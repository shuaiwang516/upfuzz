# Bug Triggering Time

**Pre-recorded traces**

To mitigate the artifact evaluation time, we reran the experiment for 24 hours 3 times to compute the average triggering time and kept the testing traces (including logs and failure reports) for **base** and **df+vd+s** (our final mode). All experiments are conducted using cloudlab `c220g5`.

## Reproduce (~10 minutes)
```bash
cd ~/project/upfuzz
bash artifact/bug-reproduction/trace/scripts/reproduce.sh

# Bug Triggering Time are saved in results.txt
cat results.txt
```

## Results
| Bug                                                                        | base (Time) | df+vd+s (Time) |
|----------------------------------------------------------------------------|-------------|----------------|
| [CASSANDRA-18105](https://issues.apache.org/jira/browse/CASSANDRA-18105)   |   13.58h    |    3.70h      |
| [CASSANDRA-18108](https://issues.apache.org/jira/browse/CASSANDRA-18108)   |   16.73h    |    17.92h     |
| [CASSANDRA-19590](https://issues.apache.org/jira/browse/CASSANDRA-19590)   |     NA      |    19.75h      |
| [CASSANDRA-19591](https://issues.apache.org/jira/browse/CASSANDRA-19591)   |     NA      |    *Skipped    |
| [CASSANDRA-19623](https://issues.apache.org/jira/browse/CASSANDRA-19623)   |     NA      |    *Skipped    |
| [CASSANDRA-19639](https://issues.apache.org/jira/browse/CASSANDRA-19639)   |     NA      |    20.84h      |
| [CASSANDRA-19689](https://issues.apache.org/jira/browse/CASSANDRA-19689)   |     NA      |    18.64h      |
| [CASSANDRA-20182](https://issues.apache.org/jira/browse/CASSANDRA-20182)   |     NA      |    18.70h      |
| [HBASE-28583](https://issues.apache.org/jira/browse/HBASE-28583)           |     NA      |    19.36h      |
| [HBASE-28812](https://issues.apache.org/jira/browse/HBASE-28812)           |   Trivial   |    Trivial     |
| [HBASE-28815](https://issues.apache.org/jira/browse/HBASE-28815)           |   Trivial   |    Trivial     |
| [HBASE-29021](https://issues.apache.org/jira/browse/HBASE-29021)           |    0.54h    |    0.45h       |
| [HDFS-16984](https://issues.apache.org/jira/browse/HDFS-16984)             |     0.36h   |    0.07h      |
| [HDFS-17219](https://issues.apache.org/jira/browse/HDFS-17219)             |    9.60h    |    2.44h       |
| [HDFS-17686](https://issues.apache.org/jira/browse/HDFS-17686)             |    9.99h    |    2.75h       |

> NA: means the bug cannot be triggered within 24 hours, the results would be 24.00 hours
> 
> Skipped: as described in the paper: Star (*) means the bug cannot be triggered consistently within 24 hours
and we record the shortest amount of time we observed. 
> 
> Trivial: once upgrade, the bug will be triggered

# Generate Traces From Scratch (24h\*6\*11=1584h=66 machine days)

You could also run the entire experiments and generate the traces. Checkout [trace-scratch.md](trace-scratch.md) for more details.