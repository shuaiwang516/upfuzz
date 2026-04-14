# CloudLab Launcher

## Prerequisites

1. Docker images must be built locally and pushed to Docker Hub first:
   ```bash
   # Build all 6 image pairs locally (run from upfuzz-shuai root)
   for args in \
     "cassandra apache-cassandra-3.11.19 apache-cassandra-4.1.10" \
     "cassandra apache-cassandra-4.1.10 apache-cassandra-5.0.6" \
     "hbase hbase-2.5.13 hbase-2.6.4" \
     "hbase hbase-2.6.4 hbase-4.0.0-alpha-1-SNAPSHOT" \
     "hdfs hadoop-2.10.2 hadoop-3.3.6" \
     "hdfs hadoop-3.3.6 hadoop-3.4.2"; do
     bash scripts/docker/build_rolling_image_pair.sh $args
   done

   # Push to Docker Hub (must be logged in: docker login)
   for img in $(docker images | grep upfuzz | awk '{print $1":"$2}'); do
     docker tag "$img" "shuaiwang516/$img"
     docker push "shuaiwang516/$img"
   done
   ```

2. Prebuilds must be uploaded to mir server at:
   `https://mir.cs.illinois.edu/~swang516/rupfuzz/prebuild/`

3. Machine list at `scripts/cloudlab-runner/machine_list.txt` (currently 6 machines).

## Usage

```bash
# Full deploy from scratch (setup + build + pull images + launch, 12h)
python3 scripts/cloudlab_launcher.py deploy --timeout-sec 43200

# Launch only (machines already set up)
python3 scripts/cloudlab_launcher.py launch --timeout-sec 43200

# Monitor (one-shot snapshot)
python3 scripts/cloudlab_launcher.py monitor

# Monitor (continuous, check every hour)
python3 scripts/cloudlab_launcher.py monitor --continuous --interval 3600

# Stop everything
python3 scripts/cloudlab_launcher.py stop

# Download results
python3 scripts/cloudlab_launcher.py download --dest /mnt/ssd/rupfuzz/cloudlab-results/apr10

# Current 6-machine list (equivalent to all machines today)
python3 scripts/cloudlab_launcher.py monitor --machines 1-6

# Explicitly force mode 5 (not needed with the current 6-machine list)
python3 scripts/cloudlab_launcher.py deploy --mode 5 --timeout-sec 43200

# Override all machines to mode 6 if you want a branch-only run
python3 scripts/cloudlab_launcher.py deploy --mode 6 --timeout-sec 43200

# Custom tag
python3 scripts/cloudlab_launcher.py deploy --tag my-experiment --timeout-sec 7200
```

## Deploy Pipeline

The `deploy` command runs these steps in order:

1. **Environment setup** — Installs Docker, Java 8/11/17, Maven, Ant on each machine (skips if Docker already present)
2. **Clone repos** — Clones `upfuzz` and `ssg-runtime` repos (shallow clone for speed)
3. **Build** — Builds ssg-runtime fatJar + upfuzz classes + copyDependencies
4. **Pull images + setup prebuilds** — Pulls pre-built Docker images from Docker Hub, then runs the build script with `FORCE_DOCKER_REBUILD=0` to download prebuilds from mir and apply config patches (num_tokens, daemon scripts, etc.)
5. **Launch** — Starts fuzzing in tmux sessions on all machines

## Machine Assignment (current default)

With the current 6-entry `scripts/cloudlab-runner/machine_list.txt`, the launcher maps one predefined job to each machine and all six assignments run in `testingMode=5` by default. The mixed `mode 5 / mode 6` behavior only applies when the machine list has more than six entries, or when `--mode` is explicitly provided.

| Machine # | Host | Job | Mode |
|-----------|------|-----|------|
| 1 | clnode270 | Cassandra 3.11→4.1 | 5 (trace) |
| 2 | clnode264 | Cassandra 4.1→5.0 | 5 (trace) |
| 3 | clnode262 | HBase 2.5→2.6 | 5 (trace) |
| 4 | clnode275 | HBase 2.6→4.0 | 5 (trace) |
| 5 | clnode282 | HDFS 2.10→3.3 | 5 (trace) |
| 6 | clnode265 | HDFS 3.3→3.4 | 5 (trace) |
