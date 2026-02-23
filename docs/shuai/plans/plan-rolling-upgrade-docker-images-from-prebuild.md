# Plan: Build Rolling-Upgrade Docker Images from Local `prebuild/` Archives

## Goal
Create rolling-upgrade Docker images for Cassandra, HDFS, and HBase using only the local archives under:

- `prebuild/cassandra/*-src-instrumented.tar.gz`
- `prebuild/hdfs/*-src-instrumented.tar.gz`
- `prebuild/hbase/*-src-instrumented.tar.gz`

No additional source downloads will be used.

## What I confirmed from docs/code

1. Image naming used by runtime:
   - Cassandra: `upfuzz_cassandra:<original>_<upgraded>`
   - HDFS: `upfuzz_hdfs:<original>_<upgraded>`
   - HBase: `upfuzz_hbase:<original>_<upgraded>`
2. Image build contexts:
   - Cassandra: `src/main/resources/cassandra/upgrade-testing/compile-src`
   - HDFS: `src/main/resources/hdfs/compile-src`
   - HBase: `src/main/resources/hbase/compile-src`
3. Images are thin wrappers; binaries are mounted at runtime from host `prebuild/` directories (not baked into image layers).
4. Cluster startup scripts in images (`cassandra-clusternode.sh`, `hdfs-clusternode.sh`) embed `ORI_VERSION`/`UP_VERSION` constants and must be updated per image build.
5. HBase also requires helper image `upfuzz_hdfs:hadoop-2.10.2` and mounted host path `prebuild/hadoop/hadoop-2.10.2`.

## Critical constraint discovered during exploration

The provided archives are source-layout packages, not ready-to-run binary distributions:

- HDFS and HBase archives do not contain runtime `bin/share/lib` distributions expected by UpFuzz runtime mounts.
- Cassandra 4.1.10 and 5.0.6 archives do not include populated runtime `lib/*.jar`; they require local build output materialization.

So the plan must include a **prebuild materialization phase** before image building.

## Target upgrade matrix (initial)

I will build adjacent forward rolling-upgrade paths first:

- Cassandra:
  - `apache-cassandra-3.11.19 -> apache-cassandra-4.1.10`
  - `apache-cassandra-4.1.10 -> apache-cassandra-5.0.6`
- HDFS:
  - `hadoop-2.10.2 -> hadoop-3.3.6`
  - `hadoop-3.3.6 -> hadoop-3.4.2`
- HBase:
  - `hbase-2.5.13 -> hbase-2.6.4`
  - `hbase-2.6.4 -> hbase-3.0.0-beta-1`

## Execution plan

### Phase 1: Extract and normalize local prebuild sources

1. Extract each archive into `prebuild/<system>/`.
2. Normalize directory names to runtime-expected names:
   - `apache-cassandra-<ver>-src` -> `apache-cassandra-<ver>`
   - `hadoop-<ver>-src` -> `hadoop-<ver>`
   - HBase already extracts as `hbase-<ver>` (keep as-is)

### Phase 2: Materialize runnable prebuild directories from source trees

1. Cassandra materialization (`apache-cassandra-*`):
   - Run `ant jar` in each extracted Cassandra directory.
   - Ensure runtime classpath artifacts exist for startup:
     - built Cassandra jar under `build/`
     - required dependency jars available under `lib/` (copy from build outputs when needed, especially 4.1/5.0).
   - Apply daemon selection:
     - 3.11.x -> `src/main/resources/cqlsh_daemon2.py`
     - 4.1.x -> `src/main/resources/cqlsh_daemon4.py`
     - 5.0.x -> `src/main/resources/cqlsh_daemon5.py`

2. HDFS materialization (`hadoop-*`):
   - Build binary distribution from each local source tree (`mvn ... -Pdist ...`).
   - Materialize runtime directories as `prebuild/hdfs/hadoop-<ver>/` with `bin/`, `sbin/`, `share/`, `etc/`.
   - Inject daemon support per version:
     - 2.10.2 -> `FsShellDaemon2.java` + old `bin/hdfs` patch
     - >=3.3.6 -> `FsShellDaemon_trunk.java` + trunk `bin/hdfs` patch

3. HBase materialization (`hbase-*`):
   - Build binary distribution from each local source tree (`mvn ... -Pdist ...`).
   - Materialize runtime directories as `prebuild/hbase/hbase-<ver>/` with `bin/`, `conf/`, `lib/`.
   - Apply runtime customizations:
     - copy `hbase-env.sh` to each version
     - for `3.0.0-beta-1`, use `hbase-env-jdk17.sh`
     - copy `hbase_daemon3.py` into each `bin/hbase_daemon.py`

4. HBase dependency Hadoop directory:
   - Materialize `prebuild/hadoop/hadoop-2.10.2/` from local `hadoop-2.10.2-src-instrumented.tar.gz` build output.
   - Copy `core-site.xml`, `hdfs-site.xml`, `hadoop-env.sh` from `src/main/resources/hdfs/hbase-pure/` into `prebuild/hadoop/hadoop-2.10.2/etc/hadoop/`.

### Phase 3: Build Docker images for rolling upgrade fuzzing

For each pair:

1. Patch version constants in clusternode script.
2. Build Docker image with expected tag.
3. Repeat for all target pairs.

Commands pattern:

- Cassandra:
  - update `src/main/resources/cassandra/upgrade-testing/compile-src/cassandra-clusternode.sh`
  - `docker build -t upfuzz_cassandra:<ori>_<up> src/main/resources/cassandra/upgrade-testing/compile-src`
- HDFS:
  - update `src/main/resources/hdfs/compile-src/hdfs-clusternode.sh`
  - `docker build -t upfuzz_hdfs:<ori>_<up> src/main/resources/hdfs/compile-src`
- HBase:
  - `docker build -t upfuzz_hdfs:hadoop-2.10.2 src/main/resources/hdfs/hbase-pure`
  - `docker build -t upfuzz_hbase:<ori>_<up> src/main/resources/hbase/compile-src`

### Phase 4: Verification

1. Confirm all expected image tags exist via `docker images`.
2. Verify each required host mount path exists and has runtime binaries:
   - Cassandra: `bin/cassandra`, `conf/cassandra.yaml`, runtime jars
   - HDFS: `bin/hdfs`, `sbin/hadoop-daemon.sh`, `share/hadoop/*`
   - HBase: `bin/hbase`, `bin/hbase-daemon.sh`, `conf/`, `lib/`
3. Dry-run one config per system with existing UpFuzz start scripts.

## Deliverables

1. Local prebuild runtime directories generated from provided source archives.
2. Built rolling-upgrade image set for Cassandra/HDFS/HBase pairs above.
3. Reproducible command/script sequence for rebuilding images later.

## Notes for execution step

During implementation, I will automate this into a repeatable script to avoid manual errors and keep clusternode version substitutions deterministic.
