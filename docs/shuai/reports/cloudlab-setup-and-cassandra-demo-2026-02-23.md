# CloudLab Setup and Cassandra Demo Report (February 23, 2026)

## Goal

Set up a brand-new Ubuntu 22.04 CloudLab machine end-to-end with `setup_env.sh`, then run a real rolling-upgrade fuzzing demo for:

- Cassandra `apache-cassandra-4.1.10 -> apache-cassandra-5.0.6`

Target host:

- `swang516@c220g5-110417.wisc.cloudlab.us`

## Work completed

## 1) Implemented and updated setup script

Created:

- `scripts/setup-cloudlab/setup_env.sh`

Key behavior implemented:

- Installs required packages and toolchains (Java 8/11/17, Docker, Maven, Ant, `ripgrep`, `iproute2`, etc.)
- Clones and checks out `rupfuzz` branch for:
  - `https://github.com/shuaiwang516/upfuzz.git`
  - `https://github.com/shuaiwang516/ssg-runtime.git`
- Builds `ssg-runtime` fat jar and copies it to `upfuzz/lib/ssgFatJar.jar`
- Builds UpFuzz (`copyDependencies`, `build -x test`)
- Validates prebuild/image prerequisites
- Supports demo-focused mode (`cassandra-demo`) and image pulling/tagging

## 2) Fixed setup failure found on real host

First setup run failed while building UpFuzz:

- Error: `Unsupported class file major version 61`
- Cause: UpFuzz Gradle wrapper/toolchain on this host required launching Gradle with Java 11.

Fix applied to `setup_env.sh`:

- Force Gradle launcher to use `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64` for:
  - ssg-runtime build (`fatJar`)
  - upfuzz build (`copyDependencies`, `build -x test`)

After this fix, setup completed successfully.

## 3) Used instrumented demo binaries from local prebuild (as requested)

Per instruction, copied existing instrumented binaries from local machine to remote host:

- Local source:
  - `prebuild/cassandra/apache-cassandra-4.1.10`
  - `prebuild/cassandra/apache-cassandra-5.0.6`
- Remote destination:
  - `/users/swang516/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-4.1.10`
  - `/users/swang516/xlab/rupfuzz/upfuzz/prebuild/cassandra/apache-cassandra-5.0.6`

Also copied demo configtests directory:

- `configtests/apache-cassandra-4.1.10_apache-cassandra-5.0.6`

## 4) Environment setup execution on remote host

Executed in tmux on remote:

```bash
bash /users/swang516/setup_env.sh \
  --pull-images \
  --image-prefix shuaiwang516 \
  --skip-demo-prebuild-download
```

Final setup status:

- Setup completed successfully at `2026-02-23 01:16:37` (host local time)
- Pulled and tagged image:
  - `shuaiwang516/upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6`
  - local tag: `upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6`

Setup log:

- `/users/swang516/setup_env.log`

## 5) Cassandra rolling-upgrade demo execution

Executed in tmux on remote:

```bash
cd /users/swang516/xlab/rupfuzz/upfuzz
scripts/runner/run_rolling_fuzzing.sh \
  --system cassandra \
  --original apache-cassandra-4.1.10 \
  --upgraded apache-cassandra-5.0.6 \
  --rounds 2 \
  --timeout-sec 7200 \
  --clients 1 \
  --testing-mode 3 \
  --node-num 1 \
  --cassandra-retry-timeout 150 \
  --use-trace false \
  --use-jaccard false \
  --run-name cloudlab_cassandra_4_1_10_to_5_0_6_demo
```

Run result summary:

- `observed_rounds: 2`
- `diff_feedback_packets: 2`
- `stop_reason: target_rounds_reached`
- `duration_sec: 591`
- `new_failure_dirs: 2`

Summary file:

- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_demo/summary.txt`

Notable server markers:

- `TestPlanDiffFeedbackPacket received` (2 times)
- `Added test plan to corpus` (2 times)

## 6) Result artifact locations

Remote demo artifacts:

- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_demo/summary.txt`
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_demo/monitor.log`
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_demo/upfuzz_server.log`
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_demo/upfuzz_client_1.log`
- `/users/swang516/xlab/rupfuzz/upfuzz/scripts/runner/results/cloudlab_cassandra_4_1_10_to_5_0_6_demo/failure_new/`

Remote setup artifacts:

- `/users/swang516/setup_env.log`
- `/users/swang516/xlab/rupfuzz/env_upfuzz.sh`

## Final status

The remote machine was successfully set up from a fresh Ubuntu 22.04 baseline, and the Cassandra rolling-upgrade fuzzing demo (`4.1.10 -> 5.0.6`) completed with 2 rounds as required.
