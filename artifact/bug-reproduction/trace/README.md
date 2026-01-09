# Triggering Traces

Provided 2 triggering traces: base and df+vd+s (our final testing mode)

| Bug                                                                        | base (Time) | df+vd+s (Time) |
|----------------------------------------------------------------------------|-------------|----------------|
| [CASSANDRA-18105](https://issues.apache.org/jira/browse/CASSANDRA-18105)   |             |                |
| [CASSANDRA-18108](https://issues.apache.org/jira/browse/CASSANDRA-18108)   |             |                |
| [CASSANDRA-19590](https://issues.apache.org/jira/browse/CASSANDRA-19590)   |     NA      |    TODO        |
| [CASSANDRA-19591](https://issues.apache.org/jira/browse/CASSANDRA-19591)   |     NA      |    *Skipped    |
| [CASSANDRA-19623](https://issues.apache.org/jira/browse/CASSANDRA-19623)   |     NA      |    *Skipped    |
| [CASSANDRA-19639](https://issues.apache.org/jira/browse/CASSANDRA-19639)   |     NA      |    20.83h      |
| [CASSANDRA-19689](https://issues.apache.org/jira/browse/CASSANDRA-19689)   |     NA      |    TODO        |
| [CASSANDRA-20182](https://issues.apache.org/jira/browse/CASSANDRA-20182)   |     NA      |    18.69h      |
| [HBASE-28583](https://issues.apache.org/jira/browse/HBASE-28583)           |     NA      |    e6,7,8      |
| [HBASE-28812](https://issues.apache.org/jira/browse/HBASE-28812)           |   Trivial   |    Trivial     |
| [HBASE-28815](https://issues.apache.org/jira/browse/HBASE-28815)           |   Trivial   |    Trivial     |
| [HBASE-29021](https://issues.apache.org/jira/browse/HBASE-29021)           |     0.53h   |    e6,7,8      |
| [HDFS-16984](https://issues.apache.org/jira/browse/HDFS-16984)             |             |                |
| [HDFS-17219](https://issues.apache.org/jira/browse/HDFS-17219)             |             |                |
| [HDFS-17686](https://issues.apache.org/jira/browse/HDFS-17686)             |             |                |


> Skipped: as described in the paper: Star (*) means the bug cannot be triggered consistently within 24 hours
and we record the shortest amount of time we observed. 
> 
> Trivial: once upgrade, the bug will be triggered

Scripts: compute the triggering time from the traces

Untar each trace, and use scripts to compute the average triggering time.

```bash

```