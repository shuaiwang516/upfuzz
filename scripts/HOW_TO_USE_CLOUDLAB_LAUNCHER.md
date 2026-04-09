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

3. Machine list at `scripts/cloudlab-runner/machine_list.txt` (12 machines).

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

# Only mode 5 machines (first 6)
python3 scripts/cloudlab_launcher.py monitor --machines 1-6

# Only mode 6 machines (second 6)
python3 scripts/cloudlab_launcher.py monitor --machines 7-12

# Force all machines to mode 5
python3 scripts/cloudlab_launcher.py deploy --mode 5 --timeout-sec 43200

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

## Machine Assignment (default)

| Machine # | Host | Job | Mode |
|-----------|------|-----|------|
| 1 | pc22 | Cassandra 3.11→4.1 | 5 (trace) |
| 2 | pc16 | Cassandra 4.1→5.0 | 5 (trace) |
| 3 | pc37 | HBase 2.5→2.6 | 5 (trace) |
| 4 | pc44 | HBase 2.6→4.0 | 5 (trace) |
| 5 | pc34 | HDFS 2.10→3.3 | 5 (trace) |
| 6 | pc48 | HDFS 3.3→3.4 | 5 (trace) |
| 7 | pc06 | Cassandra 3.11→4.1 | 6 (branch only) |
| 8 | pc27 | Cassandra 4.1→5.0 | 6 (branch only) |
| 9 | pc13 | HBase 2.5→2.6 | 6 (branch only) |
| 10 | pc41 | HBase 2.6→4.0 | 6 (branch only) |
| 11 | pc32 | HDFS 2.10→3.3 | 6 (branch only) |
| 12 | pc26 | HDFS 3.3→3.4 | 6 (branch only) |
