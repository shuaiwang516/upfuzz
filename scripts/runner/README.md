# Rolling Fuzzing Runner (`run_rolling_fuzzing.sh`)

This document explains how to run `scripts/runner/run_rolling_fuzzing.sh` on a brand-new Linux server, including environment setup, dependency installation, image preparation, and execution.

## 1) What this script does

`scripts/runner/run_rolling_fuzzing.sh` automates a rolling-upgrade fuzzing run for Cassandra/HDFS/HBase:

1. Validates required Docker image tag:
   - `upfuzz_cassandra:<old>_<new>`
   - `upfuzz_hdfs:<old>_<new>`
   - `upfuzz_hbase:<old>_<new>`
2. Generates a per-run config JSON in the result directory.
3. Optionally pre-cleans old UpFuzz processes/containers.
4. Starts server and client(s) with the generated config.
5. Monitors progress until:
   - target rounds reached, or
   - timeout hit, or
   - server exits early.
6. Collects logs, key markers, failures, and summary into `scripts/runner/results/<run_name>/`.

## 2) Required repositories and paths

Use this layout (recommended):

```text
/home/shuai/xlab/rupfuzz/
  ├── upfuzz-shuai/          # this repository
  └── ssg-runtime-shuai/     # runtime jar source (ssgFatJar.jar)
```

The runner script itself is in:

`/home/shuai/xlab/rupfuzz/upfuzz-shuai/scripts/runner/run_rolling_fuzzing.sh`

## 3) Fresh machine setup

## 3.1 Install OS packages (Ubuntu/Debian)

```bash
sudo apt-get update
sudo apt-get install -y \
  git curl ca-certificates gnupg lsb-release \
  openjdk-8-jdk openjdk-11-jdk openjdk-17-jdk \
  ant maven \
  ripgrep iproute2 \
  build-essential unzip zip tmux
```

`run_rolling_fuzzing.sh` requires `docker`, `rg`, and `ss`.

## 3.2 Install Docker Engine

```bash
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
```

Allow non-root Docker usage:

```bash
sudo usermod -aG docker "$USER"
newgrp docker
docker version
docker info >/dev/null
```

## 3.3 Clone repositories

```bash
mkdir -p /home/shuai/xlab/rupfuzz
cd /home/shuai/xlab/rupfuzz

# Use your actual repository URLs
git clone <UPFUZZ_SHUAI_REPO_URL> upfuzz-shuai
git clone <SSG_RUNTIME_SHUAI_REPO_URL> ssg-runtime-shuai
```

## 3.4 Build `ssgFatJar.jar` and copy into UpFuzz

```bash
cd /home/shuai/xlab/rupfuzz/ssg-runtime-shuai
./gradlew fatJar

cp build/libs/ssgFatJar.jar /home/shuai/xlab/rupfuzz/upfuzz-shuai/lib/ssgFatJar.jar
ls -lh /home/shuai/xlab/rupfuzz/upfuzz-shuai/lib/ssgFatJar.jar
```

## 3.5 Build UpFuzz Java artifacts and runtime dependencies

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz-shuai
./gradlew copyDependencies
./gradlew build -x test
```

Note: `build.gradle` currently uses `/usr/lib/jvm/java-17-openjdk-amd64` for Java tool execution. On non-Ubuntu environments, adjust that path if needed.

## 4) Prebuild source sync

`scripts/docker/build_rolling_image_pair.sh` auto-fetches only the required tarballs from:

`https://mir.cs.illinois.edu/~swang516/rupfuzz/prebuild/`

Files are downloaded into:

`/home/shuai/xlab/rupfuzz/upfuzz-shuai/prebuild/`

Optional manual fetch for one version:

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz-shuai
wget -O prebuild/cassandra/apache-cassandra-4.1.10.tar.gz \
  "https://mir.cs.illinois.edu/~swang516/rupfuzz/prebuild/cassandra/apache-cassandra-4.1.10.tar.gz"
```

## 5) Build Docker images required by runner

Use `scripts/docker/build_rolling_image_pair.sh` to run RUN.md upgrade-testing docker prep/build per pair.
It dispatches docker build commands to:
- `scripts/docker/build_cassandra_docker.sh`
- `scripts/docker/build_hdfs_docker.sh`
- `scripts/docker/build_hbase_docker.sh`
By default docker builds are forced (`FORCE_DOCKER_REBUILD=1`, i.e., `--no-cache --pull`).
Set `FORCE_DOCKER_REBUILD=0` to disable forced rebuild.

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz-shuai

scripts/docker/build_rolling_image_pair.sh cassandra apache-cassandra-3.11.19 apache-cassandra-4.1.10
scripts/docker/build_rolling_image_pair.sh cassandra apache-cassandra-4.1.10 apache-cassandra-5.0.6

scripts/docker/build_rolling_image_pair.sh hdfs hadoop-2.10.2 hadoop-3.3.6
scripts/docker/build_rolling_image_pair.sh hdfs hadoop-3.3.6 hadoop-3.4.2

scripts/docker/build_rolling_image_pair.sh hbase hbase-2.5.13 hbase-2.6.4
scripts/docker/build_rolling_image_pair.sh hbase hbase-2.6.4 hbase-3.0.0-beta-1
```

The HBase flow also prepares/builds dependency image `upfuzz_hdfs:hadoop-2.10.2` automatically if missing.

Verify images:

```bash
docker images --format '{{.Repository}}:{{.Tag}}' | rg '^upfuzz_' | sort
```

## 6) Config metadata requirement (`configtests`)

`run_rolling_fuzzing.sh` generates config with:

`"configDir": "configtests"`

So the pair directory must exist under `configtests/`, for example:

`configtests/apache-cassandra-4.1.10_apache-cassandra-5.0.6`

If the pair metadata directory is missing, generate/copy the needed metadata before running that pair.

Check available pair metadata:

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz-shuai
find configtests -maxdepth 1 -mindepth 1 -type d -printf '%f\n' | sort
```

## 7) Run command examples

## 7.1 Cassandra demo (4.1.10 -> 5.0.6, 2 rounds)

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz-shuai

scripts/runner/run_rolling_fuzzing.sh \
  --system cassandra \
  --original apache-cassandra-4.1.10 \
  --upgraded apache-cassandra-5.0.6 \
  --rounds 2 \
  --timeout-sec 7200 \
  --clients 1 \
  --testing-mode 3 \
  --node-num 1 \
  --use-trace false \
  --use-jaccard false \
  --run-name cassandra_4_1_10_to_5_0_6_demo_node1_notrace_two_rounds
```

## 7.2 Other prepared pairs

```bash
# Cassandra
scripts/runner/run_rolling_fuzzing.sh --system cassandra --original apache-cassandra-3.11.19 --upgraded apache-cassandra-4.1.10 --rounds 2
scripts/runner/run_rolling_fuzzing.sh --system cassandra --original apache-cassandra-4.1.10 --upgraded apache-cassandra-5.0.6 --rounds 2

# HDFS
scripts/runner/run_rolling_fuzzing.sh --system hdfs --original hadoop-2.10.2 --upgraded hadoop-3.3.6 --rounds 2
scripts/runner/run_rolling_fuzzing.sh --system hdfs --original hadoop-3.3.6 --upgraded hadoop-3.4.2 --rounds 2

# HBase
scripts/runner/run_rolling_fuzzing.sh --system hbase --original hbase-2.5.13 --upgraded hbase-2.6.4 --rounds 2
scripts/runner/run_rolling_fuzzing.sh --system hbase --original hbase-2.6.4 --upgraded hbase-3.0.0-beta-1 --rounds 2
```

If a pair is missing `configtests/<old>_<new>`, that run will not be fully usable until metadata is added.

## 8) Runner options

Show all options:

```bash
scripts/runner/run_rolling_fuzzing.sh --help
```

Common options:

| Option | Meaning |
|---|---|
| `--system` | `cassandra`, `hdfs`, or `hbase` |
| `--original` / `--upgraded` | version pair |
| `--rounds` | target rounds before stop |
| `--timeout-sec` | hard timeout |
| `--clients` | number of fuzzing clients |
| `--testing-mode` | `3` (smoke/example), `5` (rolling-focused) |
| `--diff-lane-timeout-sec` | differential lane timeout for all systems |
| `--cassandra-retry-timeout` | cqlsh retry timeout for Cassandra |
| `--node-num` | override node count (default cass=2,hdfs=3,hbase=3) |
| `--run-name` | output directory name |
| `--server-port` / `--client-port` | base ports (auto-shifted if occupied) |
| `--use-trace` / `--use-jaccard` / `--use-branch-coverage` | feedback toggles |
| `--enable-log-check` | enable error-log oracle |
| `--skip-pre-clean` | skip initial `bin/clean.sh --force` |

## 9) Output and results

Each run writes to:

`scripts/runner/results/<run_name>/`

Key files:

| File | Purpose |
|---|---|
| `summary.txt` | final run summary and stop reason |
| `config.json` | generated config used for this run |
| `metadata.env` | all resolved run parameters |
| `monitor.log` | time series: elapsed, rounds, diff packet count |
| `upfuzz_server.log` | server runtime log copy |
| `upfuzz_client_1.log` | client runtime log copy |
| `server_key_markers.log` | extracted key lines from server log |
| `failure_new/` | failures created during this run |

Quick inspect:

```bash
cd /home/shuai/xlab/rupfuzz/upfuzz-shuai
RUN_DIR="scripts/runner/results/<run_name>"
cat "${RUN_DIR}/summary.txt"
tail -n 40 "${RUN_DIR}/server_key_markers.log"
```

## 10) Important behavior and caveats

1. The script runs `bin/clean.sh --force` before and after run by default.
2. `bin/clean.sh` kills UpFuzz-related Java processes and removes `upfuzz_` Docker containers.
3. Do not run multiple UpFuzz jobs on the same host unless you isolate ports and cleanup behavior.
4. For HBase runs, image `upfuzz_hdfs:hadoop-2.10.2` must exist.
5. Script exits `2` if target rounds were not reached.

## 11) Troubleshooting

1. Error: `Docker image not found: upfuzz_<system>:<old>_<new>`
   - Build image first with `scripts/docker/build_rolling_image_pair.sh`.
2. Error: `Missing command: rg` or `Missing command: ss`
   - Install `ripgrep` and `iproute2`.
3. Build failure due to Java path/toolchain mismatch
   - Ensure JDK 8/11/17 are installed.
   - Check Java path in `build.gradle` (`/usr/lib/jvm/java-17-openjdk-amd64`).
4. HBase run fails on dependency image
   - Build with `scripts/docker/build_rolling_image_pair.sh hbase ...` to auto-build `upfuzz_hdfs:hadoop-2.10.2`.
5. No progress in rounds
   - Inspect `scripts/runner/results/<run_name>/upfuzz_server.log`.
   - Check container status in `docker_ps_before_cleanup.txt`.
   - Increase `--timeout-sec` and retry.
