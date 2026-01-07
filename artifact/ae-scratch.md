# Reproducing the Results from Scratch

To streamline the artifact evaluation (AE) process, we provided pre-built instrumented binaries to enable a push-button evaluation workflow, shown in [artifact/README.md](artifact/README.md).

Reviewers could also reproduce results entirely from scratch; however, this requires a substantial amount of computation time.

## 1. Creating the Instrumented Binary

### 1.1 Source Code Analysis

- Repository: `vasco`: https://github.com/zlab-purdue/vasco
- Identify (1) which objects are serialized to disk and (2) where to monitor them 

### 1.2 Source Code Instrumentation

- Repository: `dinv-monitor`: https://github.com/zlab-purdue/dinv-monitor
- Instrument hooks to collect data format feedbacks during test execution

### 1.3 Data Format Runtime Collection (feedback)

- Repository: `ssg-runtime`: https://github.com/zlab-purdue/ssg-runtime
- Collect data format feedback during test execution and check whether the collected feedback is interesting.

## 2. Testing

- Repositories: `upfuzz`: https://github.com/zlab-purdue/upfuzz

### Reproducing Tables 2

**Expected time:** approximately **450 machine-days**.

For each version pair, we configure upfuzz to test under multiple settings and repeat each experiment three times.  
Scripts are provided to automatically check whether a failure is successfully triggered.

### Reproducing Figure 14

**Expected time:** approximately **27 machine-days**.

We configure upfuzz to run in the state-exploration mode.

### Reproducing Table 4

This experiment follows the same procedure described in [artifact/README.md](artifact/README.md).


# Misc (Not for reviewers)
```bash
# set remote url with ssh
git remote set-url origin git@github.com:zlab-purdue/upfuzz.git

# tag and push images to docker hub
docker tag \
  upfuzz_cassandra:apache-cassandra-2.2.19_apache-cassandra-3.0.30 \
  hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30
docker push hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30

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