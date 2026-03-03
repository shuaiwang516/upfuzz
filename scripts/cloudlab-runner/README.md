# CloudLab Push-Button Runner

This directory provides entrypoint scripts to run one rolling-upgrade fuzzing job per machine.

## What This Runs

- Demo/short-run script: `scripts/cloudlab-runner/run_cloudlab_job.sh`
- Continuous-fuzzing script: `scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh`
- Under the hood it:
1. builds required Docker image(s) via `scripts/docker/build_rolling_image_pair.sh` (and downloads needed prebuild tarballs from mirror if missing),
2. builds `upfuzz` Java classes,
3. launches `scripts/runner/run_rolling_fuzzing.sh` with trace + tri-diff enabled,
4. copies key artifacts into `scripts/cloudlab-runner/results/<run_name>`.

## Predefined 6-Job Mapping

- `1`: Cassandra `apache-cassandra-3.11.19 -> apache-cassandra-4.1.10`
- `2`: Cassandra `apache-cassandra-4.1.10 -> apache-cassandra-5.0.6`
- `3`: HBase `hbase-2.5.13 -> hbase-2.6.4`
- `4`: HBase `hbase-2.6.4 -> hbase-3.0.0-beta-1`
- `5`: HDFS `hadoop-2.10.2 -> hadoop-3.3.6`
- `6`: HDFS `hadoop-3.3.6 -> hadoop-3.4.2`

Check mapping quickly:

```bash
scripts/cloudlab-runner/run_cloudlab_job.sh --list-jobs
```

## Prerequisites On Each Machine

1. Repo is present and on branch `rupfuzz`.
2. Dependencies installed (Java, Gradle wrapper deps, Docker, `rg`, `ss`, `tar`, `wget`).
3. Docker daemon is running and your user can run Docker commands.
4. Network access to prebuild mirror `https://mir.cs.illinois.edu/~swang516/rupfuzz/prebuild/` (unless required tarballs already exist in local `prebuild/`).

If machine is fresh Ubuntu 22.04, run:

```bash
scripts/setup-cloudlab/setup_env.sh
```

## Minimal Run Commands (6 Machines)

On each CloudLab machine:

```bash
cd /path/to/upfuzz-shuai
scripts/cloudlab-runner/run_cloudlab_job.sh --job-id <1..6>
```

Example for machine assigned HDFS 2.10.2 -> 3.3.6:

```bash
scripts/cloudlab-runner/run_cloudlab_job.sh --job-id 5
```

## Continuous Fuzzing (No 1-2 Round Stop)

Use the continuous wrapper:

```bash
scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh --job-id <1..6>
```

Default behavior:

- `--rounds 2147483647`
- `--timeout-sec 2147483647`

so the run does not stop at demo-scale round counts.

Detached launch example:

```bash
scripts/cloudlab-runner/run_cloudlab_fuzz_job.sh \
  --job-id 2 \
  --run-name cloudlab_fuzz_job2 \
  --detach
```

Stop detached fuzzing:

```bash
bin/clean.sh --force
```

## Recommended tmux Usage

```bash
tmux new -s upfuzz-job
cd /path/to/upfuzz-shuai
scripts/cloudlab-runner/run_cloudlab_job.sh --job-id <1..6>
```

Detach: `Ctrl+b d`

## Important Options

```bash
scripts/cloudlab-runner/run_cloudlab_job.sh \
  --job-id 2 \
  --run-name cloudlab_cassandra_410_506 \
  --rounds 1 \
  --timeout-sec 5400
```

- `--job-id`: select one of the six predefined jobs.
- `--run-name`: stable folder name for easier collection.
- `--rounds`: number of differential rounds to wait for.
- `--timeout-sec`: hard timeout.
- `--hbase-daemon-retry-times`: HBase shell daemon retry attempts for startup-heavy environments.
- `--skip-docker-build`: skip `scripts/docker/build_rolling_image_pair.sh`.
- `--skip-build`: skip `./gradlew classes -x test`.
- `--skip-pull`: deprecated alias for `--skip-docker-build`.

Manual pair mode (without job-id):

```bash
scripts/cloudlab-runner/run_cloudlab_job.sh \
  --system cassandra \
  --original apache-cassandra-4.1.10 \
  --upgraded apache-cassandra-5.0.6
```

## Output Locations

Primary output:

- `scripts/cloudlab-runner/results/<run_name>/launch.log`
- `scripts/cloudlab-runner/results/<run_name>/summary.txt`
- `scripts/cloudlab-runner/results/<run_name>/upfuzz_server.log`
- `scripts/cloudlab-runner/results/<run_name>/upfuzz_client_1.log`
- `scripts/cloudlab-runner/results/<run_name>/server_key_markers.log`
- `scripts/cloudlab-runner/results/<run_name>/client_key_markers.log`

Runner-native output (full details):

- `scripts/runner/results/<run_name>/`

## Verification Fields to Check

In `summary.txt`, verify:

- `observed_rounds: 1` (or your target)
- `diff_feedback_packets: >= 1`
- `trace_signal_ok: true`
- `trace_merged_old_nonzero_count: >= 1`
- `trace_merged_rolling_nonzero_count: >= 1`
- `trace_merged_new_nonzero_count: >= 1`
- `message_tri_diff_count: >= 1`

Notes:

- `trace_len_zero_count` is node-level and may be non-zero for HDFS because node1/node2 shell-daemon traces can be empty in a round.
- Execution-level success for diff tracing is represented by merged trace counts (`trace_merged_*_nonzero_count`) and `message_tri_diff_count`.

## Common Failures

- `Docker image not found`: docker build step failed or was skipped while image was absent.
- `Path not writable: /tmp/upfuzz/hdfs`: fix ownership/permission for HDFS tmp root.
- `Trace signal missing`: check prebuild tarball availability/instrumentation, `useTrace=true`, and server/client logs.
- HBase specific: `Trace signal missing` can happen even with `observed_rounds: 3` when each lane returns null feedback (`differential lane ... returned null feedback packet`), producing only zero-length traces.
- HBase startup flakiness: repeated `cannot connect to hbase shell` can be mitigated by increasing retries, for example `--hbase-daemon-retry-times 120`.

## Collecting Results From Each Machine

Example:

```bash
scp -r user@host:/path/to/upfuzz-shuai/scripts/cloudlab-runner/results/<run_name> ./
```
