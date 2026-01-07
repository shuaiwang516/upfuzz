# Reproducing the Results from Scratch

To streamline the artifact evaluation (AE) process, we provide pre-built instrumented binaries (Step 1) and pre-recorded bug-triggering traces, enabling a push-button evaluation workflow.  
Reviewers could also reproduce results entirely from scratch; however, this requires a substantial amount of computation time.

All experiments were originally conducted in parallel using a large number of servers.

---

## 1. Creating the Instrumented Binary

### 1.1 Source Code Analysis

- Repository: `vasco`: https://github.com/zlab-purdue/vasco
- Identify (1) which objects are serialized to disk and (2) where to monitor them 

### 1.2 Source Code Instrumentation

- Repository: `dinv-monitor`: https://github.com/zlab-purdue/dinv-monitor
- Instrument hooks to ssg-runtime (collect feedbacks) based on information from 1.1

### 1.3 data format runtime collection (feedback)

- Repository: `ssg-runtime`: https://github.com/zlab-purdue/ssg-runtime
- Collect data format feedback during test execution and check whether the collected feedback is interesting.

---

## 2. Fuzz Testing

- Repositories: `upfuzz`

### Reproducing Tables 2 and 3

**Expected time:** approximately **450 machine-days**.

For each version pair, we configure UpFuzz to test under multiple settings and repeat each experiment three times.  
Scripts are provided to automatically check whether a failure is successfully triggered.

---

### Reproducing Figure 14

**Expected time:** approximately **27 machine-days**.

We configure UpFuzz to run in the state-exploration mode.

---

### Reproducing Table 4

This experiment follows the same procedure described in `ae.md`.


## Misc (Not for reviewers)
```bash
# set remote url with ssh
git remote set-url origin git@github.com:zlab-purdue/upfuzz.git

# tag and push images to docker hub
docker tag \
  upfuzz_cassandra:apache-cassandra-2.2.19_apache-cassandra-3.0.30 \
  hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30
docker push hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30

# HBase
docker tag \
  upfuzz_hbase:hbase-2.4.18_hbase-2.5.9 \
  hanke580/upfuzz-ae:hbase-2.4.18_2.5.9
docker push hanke580/upfuzz-ae:hbase-2.4.18_2.5.9

docker tag \
  upfuzz_hbase:hbase-2.5.9_hbase-3.0.0 \
  hanke580/upfuzz-ae:hbase-2.5.9_3.0.0
docker push hanke580/upfuzz-ae:hbase-2.5.9_3.0.0

docker tag \
  upfuzz_hbase:hbase-1.7.2_hbase-2.6.0 \
  hanke580/upfuzz-ae:hbase-1.7.2_2.6.0
docker push hanke580/upfuzz-ae:hbase-1.7.2_2.6.0

docker tag \
  upfuzz_hbase:hbase-2.6.0_hbase-3.0.0 \
  hanke580/upfuzz-ae:hbase-2.6.0_3.0.0
docker push hanke580/upfuzz-ae:hbase-2.6.0_3.0.0

docker tag \
  upfuzz_hdfs:hadoop-2.10.2_hadoop-3.3.6 \
  hanke580/upfuzz-ae:hdfs-2.10.2_3.3.6
docker push hanke580/upfuzz-ae:hdfs-2.10.2_3.3.6

# pull images from docker hub
docker pull hanke580/upfuzz-ae:cassandra-3.11.17_4.1.4
docker tag \
  hanke580/upfuzz-ae:cassandra-3.11.17_4.1.4 \
  upfuzz_cassandra:apache-cassandra-3.11.17_apache-cassandra-4.1.4

gh release create cassandra-4.1.6 \
  apache-cassandra-4.1.6-bin.tar.gz \
  --title "Official Binary" \
  --notes "Testing Purpose"

awk 'NR==1{split($0,a,":|,"); t1=(a[1]*3600+a[2]*60+a[3])*1000+a[4]} NR==2{split($0,a,":|,"); t2=(a[1]*3600+a[2]*60+a[3])*1000+a[4]} NR==3{split($0,a,":|,"); t3=(a[1]*3600+a[2]*60+a[3])*1000+a[4]} END{printf "t1->t2: %.3fs\nt2->t3: %.3fs\nt1->t3: %.3fs\n",(t2-t1)/1000,(t3-t2)/1000,(t3-t1)/1000}' < <(
  grep "Connect to cqlsh" client.log | head -n 1 | awk '{print $2}'
  grep "Cqlsh connected" client.log | head -n 1 | awk '{print $2}'
  grep "collect coverage" client.log | head -n 1 | awk '{print $2}'
)

# clean up
bin/clean.sh; bin/rm.sh
rm -rf /tmp/upfuzz 
sudo rm -rf prebuild
git checkout .
git pull
```