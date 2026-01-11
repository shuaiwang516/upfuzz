# Reproducing the Results from Scratch

To streamline the artifact evaluation (AE) process, we provided pre-built instrumented binaries to enable a push-button evaluation workflow, shown in [artifact/README.md](artifact/README.md).

Reviewers could also reproduce results entirely from scratch.

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

Use the created instrumented binary to replace the provided instrumented binary as described in [README.md](README.md).


# Misc (Not for reviewers)
```bash
# set remote url with ssh
git remote set-url origin git@github.com:zlab-purdue/upfuzz.git

# tag and push images to docker hub
docker tag \
  upfuzz_cassandra:apache-cassandra-2.2.19_apache-cassandra-3.0.30 \
  hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30
docker push hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30
```