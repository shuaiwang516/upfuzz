# Scripts Guide: Fresh Machine Setup and Rolling Fuzzing Run

This document explains how to use:
- `scripts/setup-cloudlab/setup_env.sh` to prepare a brand-new Ubuntu 22.04 machine
- `scripts/runner/run_rolling_fuzzing.sh` to launch rolling-upgrade fuzzing runs

## 1) Scope and assumptions

- OS: Ubuntu 22.04 (fresh install)
- You have `sudo` privileges
- You want to run UpFuzz rolling-upgrade fuzzing using the `rupfuzz` branch
- For Cassandra demo, you use this image from Docker Hub:
  - `shuaiwang516/upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6`

## 2) Required repositories

Use these repos/branches:

```bash
git clone https://github.com/shuaiwang516/upfuzz.git && cd upfuzz && git checkout rupfuzz
cd ..
git clone https://github.com/shuaiwang516/ssg-runtime.git && cd ssg-runtime && git checkout rupfuzz
```

Notes:
- `setup_env.sh` can also clone/update these repos automatically.
- Default workspace used by `setup_env.sh` is `~/xlab/rupfuzz`.

## 3) Required prebuild archives

For full mode (`--mode full`), all 9 instrumented archives are required:
- Cassandra: `3.11.19`, `4.1.10`, `5.0.6`
- HDFS: `2.10.2`, `3.3.6`, `3.4.2`
- HBase: `2.5.13`, `2.6.4`, `3.0.0-beta-1`

If archives are on another machine, copy them to the new server first.
Example (run from your local machine):

```bash
scp /path/to/prebuild/cassandra/apache-cassandra-4.1.10-src-instrumented.tar.gz <user>@<host>:/tmp/
scp /path/to/prebuild/cassandra/apache-cassandra-5.0.6-src-instrumented.tar.gz <user>@<host>:/tmp/
```

The prebuild pathes on this local machines are in 
```
/home/shuai/xlab/rupfuzz/prebuild
```

You should copy these prebuild archives to the target machine (e.g., CloudLab VM) and provide the directory path to `setup_env.sh` via `--prebuild-source-dir` option. The cloudlab prebuild pathes are in 
```
/users/swang516/xlab/rupfuzz/upfuzz/prebuild
```
And you should copy the prebuild archives to this directory for all cloudlab machines.

```bash

## 4) Run environment setup script

Recommended to run inside `tmux`.

### 4.1 Cassandra demo setup (recommended for first validation)

```bash
cd /path/to/upfuzz/scripts/setup-cloudlab
chmod +x setup_env.sh

./setup_env.sh \
  --mode cassandra-demo \
  --prebuild-source-dir /tmp \
  --pull-images \
  --image-prefix shuaiwang516
```

What this does:
- Installs system dependencies (Java 8/11/17, Docker, Maven, Ant, rg, etc.)
- Clones/updates `upfuzz` and `ssg-runtime` on `rupfuzz`
- Builds `ssgFatJar.jar` and copies it into UpFuzz
- Builds UpFuzz (`copyDependencies`, `build -x test`)
- Materializes Cassandra prebuild directories from instrumented source archives
- Verifies instrumentation hook exists (`NetTraceRuntimeBridge.java`)
- Pulls/tags required Docker image locally as:
  - `upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6`

### 4.2 Full setup (all systems)

```bash
./setup_env.sh \
  --mode full \
  --prebuild-source-dir /path/to/all-prebuild-archives \
  --pull-images \
  --image-prefix shuaiwang516
```

## 5) Verify setup success

Run these checks:

```bash
# Repos and branch
cd ~/xlab/rupfuzz/upfuzz && git rev-parse --abbrev-ref HEAD
cd ~/xlab/rupfuzz/ssg-runtime && git rev-parse --abbrev-ref HEAD

# Core artifacts
ls -lh ~/xlab/rupfuzz/upfuzz/lib/ssgFatJar.jar

# Cassandra prebuild directories (demo mode)
ls -ld ~/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-4.1.10
ls -ld ~/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-5.0.6

# Instrumentation hook present
ls ~/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-4.1.10/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java
ls ~/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-5.0.6/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java

# Docker image available locally
docker image inspect upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6 >/dev/null && echo OK
```

## 6) Run rolling fuzzing with runner script

Runner script:
- `scripts/runner/run_rolling_fuzzing.sh`
- Results go to: `scripts/runner/results/<run_name>/`

### 6.1 Cassandra demo command (trace verification)

Use `node-num 2` for reliable network-trace verification.

```bash
cd ~/xlab/rupfuzz/upfuzz
chmod +x scripts/runner/run_rolling_fuzzing.sh

scripts/runner/run_rolling_fuzzing.sh \
  --system cassandra \
  --original apache-cassandra-4.1.10 \
  --upgraded apache-cassandra-5.0.6 \
  --rounds 2 \
  --timeout-sec 7200 \
  --clients 1 \
  --testing-mode 3 \
  --node-num 2 \
  --use-trace true \
  --use-jaccard true \
  --require-trace-signal \
  --run-name cassandra_4_1_10_to_5_0_6_demo
```

### 6.2 Important runner options

- `--rounds <N>`: stop after N completed rounds
- `--timeout-sec <N>`: hard timeout
- `--node-num <N>`: cluster node count
- `--use-trace true|false`: enable network trace collection
- `--require-trace-signal`: fail run if trace signal is missing
- `--skip-pre-clean`: skip pre-run `bin/clean.sh`

## 7) Check run results

Open:
- `scripts/runner/results/<run_name>/summary.txt`
- `scripts/runner/results/<run_name>/upfuzz_server.log`
- `scripts/runner/results/<run_name>/upfuzz_client_1.log`

For trace-enabled Cassandra verification, confirm in `summary.txt`:
- `trace_signal_ok: true`
- `trace_connect_refused_count: 0`
- `trace_received_count: > 0`
- `trace_len_positive_count: > 0`

## 8) Troubleshooting

- Docker permission denied:
  - Re-login after setup, or run `newgrp docker`.
- Trace shows `Connection refused` and zero trace lengths:
  - Re-run with `--node-num 2` and `--require-trace-signal`.
- Missing prebuild archive errors in setup:
  - Provide archives under `prebuild/...` or use `--prebuild-source-dir`.
- Image missing in runner:
  - Pull/tag with `setup_env.sh --pull-images --image-prefix <prefix>`.
- Cleanup stuck resources:

```bash
cd ~/xlab/rupfuzz/upfuzz
bin/clean.sh --force
```

## 9) Quick command sequence (demo)

```bash
# 1) Setup
cd /path/to/upfuzz/scripts/setup-cloudlab
./setup_env.sh --mode cassandra-demo --prebuild-source-dir /tmp --pull-images --image-prefix shuaiwang516

# 2) Run
cd ~/xlab/rupfuzz/upfuzz
scripts/runner/run_rolling_fuzzing.sh \
  --system cassandra \
  --original apache-cassandra-4.1.10 \
  --upgraded apache-cassandra-5.0.6 \
  --rounds 2 \
  --node-num 2 \
  --use-trace true \
  --require-trace-signal \
  --run-name cassandra_demo

# 3) Inspect
cat scripts/runner/results/cassandra_demo/summary.txt
```
