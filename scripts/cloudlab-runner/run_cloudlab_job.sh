#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNNER_SCRIPT="${ROOT_DIR}/scripts/runner/run_rolling_fuzzing.sh"
RESULTS_ROOT="${SCRIPT_DIR}/results"
mkdir -p "${RESULTS_ROOT}"

NAMESPACE="${NAMESPACE:-shuaiwang516}"
JOB_ID=""
SYSTEM=""
ORIGINAL_VERSION=""
UPGRADED_VERSION=""
RUN_NAME=""
ROUNDS=1
TIMEOUT_SEC=3600
CLIENTS=1
TESTING_MODE=5
NODE_NUM=""
SKIP_BUILD=false
SKIP_PULL=false

usage() {
    cat <<'USAGE'
Usage:
  run_cloudlab_job.sh [options]

Options:
  --job-id <1..6>                    Predefined job mapping for 6-machine split
  --system <cassandra|hbase|hdfs>    Manual system selection (if no --job-id)
  --original <version>               Manual original version (if no --job-id)
  --upgraded <version>               Manual upgraded version (if no --job-id)
  --run-name <name>                  Explicit runner result folder name
  --rounds <N>                       Number of rounds (default: 1)
  --timeout-sec <N>                  Runner timeout in seconds (default: 3600)
  --clients <N>                      Number of clients (default: 1)
  --testing-mode <N>                 Upfuzz testing mode (default: 5)
  --node-num <N>                     Override node number
  --namespace <docker-namespace>     Docker namespace to pull from (default: shuaiwang516)
  --skip-build                       Skip './gradlew classes -x test'
  --skip-pull                        Skip docker pull/tag
  --list-jobs                        Print job-id mapping and exit
  -h, --help                         Show this help

Examples:
  run_cloudlab_job.sh --job-id 1
  run_cloudlab_job.sh --job-id 5 --run-name cloudlab_hdfs_2102_336
  run_cloudlab_job.sh --system cassandra --original apache-cassandra-4.1.10 --upgraded apache-cassandra-5.0.6
USAGE
}

log() {
    printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

ensure_docker_compose() {
    if docker compose version >/dev/null 2>&1; then
        return 0
    fi

    if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
        log "docker compose is missing; attempting to install docker-compose-v2"
        sudo -n apt-get update >/dev/null 2>&1 || true
        sudo -n apt-get install -y docker-compose-v2 >/dev/null 2>&1 || true
        if docker compose version >/dev/null 2>&1; then
            log "docker compose installed successfully"
            return 0
        fi
    fi

    die "docker compose is required. Install docker-compose-v2 (or docker-compose-plugin) and retry."
}

print_jobs() {
    cat <<'JOBS'
Job Mapping (6 machines):
  1 -> cassandra  apache-cassandra-3.11.19      -> apache-cassandra-4.1.10
  2 -> cassandra  apache-cassandra-4.1.10       -> apache-cassandra-5.0.6
  3 -> hbase      hbase-2.5.13                  -> hbase-2.6.4
  4 -> hbase      hbase-2.6.4                   -> hbase-3.0.0-beta-1
  5 -> hdfs       hadoop-2.10.2                 -> hadoop-3.3.6
  6 -> hdfs       hadoop-3.3.6                  -> hadoop-3.4.2
JOBS
}

assign_job() {
    case "$1" in
        1)
            SYSTEM="cassandra"
            ORIGINAL_VERSION="apache-cassandra-3.11.19"
            UPGRADED_VERSION="apache-cassandra-4.1.10"
            ;;
        2)
            SYSTEM="cassandra"
            ORIGINAL_VERSION="apache-cassandra-4.1.10"
            UPGRADED_VERSION="apache-cassandra-5.0.6"
            ;;
        3)
            SYSTEM="hbase"
            ORIGINAL_VERSION="hbase-2.5.13"
            UPGRADED_VERSION="hbase-2.6.4"
            ;;
        4)
            SYSTEM="hbase"
            ORIGINAL_VERSION="hbase-2.6.4"
            UPGRADED_VERSION="hbase-3.0.0-beta-1"
            ;;
        5)
            SYSTEM="hdfs"
            ORIGINAL_VERSION="hadoop-2.10.2"
            UPGRADED_VERSION="hadoop-3.3.6"
            ;;
        6)
            SYSTEM="hdfs"
            ORIGINAL_VERSION="hadoop-3.3.6"
            UPGRADED_VERSION="hadoop-3.4.2"
            ;;
        *)
            die "Unsupported --job-id: $1 (expected 1..6)"
            ;;
    esac
}

ensure_prebuild_materialized() {
    local system="$1"
    local version="$2"
    local base="${ROOT_DIR}/prebuild/${system}"
    local dir="${base}/${version}"
    local archive="${base}/${version}-src-instrumented.tar.gz"
    local extracted_src="${base}/${version}-src"

    if [[ -d "${dir}" ]]; then
        return
    fi
    [[ -f "${archive}" ]] || die "Missing prebuild archive: ${archive}"

    log "Extracting prebuild archive: ${archive}"
    tar -xzf "${archive}" -C "${base}"
    if [[ -d "${extracted_src}" && ! -d "${dir}" ]]; then
        mv "${extracted_src}" "${dir}"
    fi
    [[ -d "${dir}" ]] || die "Failed to materialize prebuild dir: ${dir}"
}

ensure_hdfs_tmp_root_writable() {
    local root="/tmp/upfuzz/hdfs"

    mkdir -p "${root}" 2>/dev/null || true
    if [[ ! -d "${root}" ]]; then
        if command -v sudo >/dev/null 2>&1; then
            sudo -n mkdir -p "${root}" 2>/dev/null || true
        fi
    fi

    if [[ -d "${root}" && ! -w "${root}" ]]; then
        if command -v sudo >/dev/null 2>&1; then
            sudo -n chown -R "$(id -u):$(id -g)" /tmp/upfuzz 2>/dev/null || true
            sudo -n chmod -R u+rwx /tmp/upfuzz 2>/dev/null || true
        fi
    fi

    [[ -d "${root}" && -w "${root}" ]] || die "Path not writable: ${root}. Fix ownership/permissions before running HDFS jobs."
}

ensure_hbase_hadoop_alias() {
    local hdfs_dir="${ROOT_DIR}/prebuild/hdfs/hadoop-2.10.2"
    local hadoop_parent="${ROOT_DIR}/prebuild/hadoop"
    local hadoop_dir="${hadoop_parent}/hadoop-2.10.2"
    local required_conf="${hdfs_dir}/etc/hadoop/hdfs-site.xml"

    [[ -d "${hdfs_dir}" ]] || die "Missing extracted HDFS base for HBase: ${hdfs_dir}"
    mkdir -p "${hadoop_parent}"

    # If extracted hdfs tree lacks runtime conf files, do not symlink; a later
    # hydration step will copy a complete runtime tree from docker image.
    if [[ ! -f "${required_conf}" ]]; then
        return
    fi

    if [[ -L "${hadoop_dir}" ]]; then
        return
    fi
    if [[ -e "${hadoop_dir}" && ! -d "${hadoop_dir}" ]]; then
        rm -f "${hadoop_dir}"
    fi
    if [[ -d "${hadoop_dir}" ]]; then
        return
    fi

    ln -s "../hdfs/hadoop-2.10.2" "${hadoop_dir}"
}

refresh_hbase_hadoop_runtime_config() {
    local conf_src="${ROOT_DIR}/src/main/resources/hdfs/hbase-pure"
    local hadoop_conf_dir="${ROOT_DIR}/prebuild/hadoop/hadoop-2.10.2/etc/hadoop"
    local hdfs_conf_dir="${ROOT_DIR}/prebuild/hdfs/hadoop-2.10.2/etc/hadoop"

    mkdir -p "${hadoop_conf_dir}"
    cp -f "${conf_src}/core-site.xml" "${hadoop_conf_dir}/core-site.xml"
    cp -f "${conf_src}/hdfs-site.xml" "${hadoop_conf_dir}/hdfs-site.xml"
    cp -f "${conf_src}/hadoop-env.sh" "${hadoop_conf_dir}/hadoop-env.sh"

    if [[ -d "${hdfs_conf_dir}" ]]; then
        cp -f "${conf_src}/core-site.xml" "${hdfs_conf_dir}/core-site.xml"
        cp -f "${conf_src}/hdfs-site.xml" "${hdfs_conf_dir}/hdfs-site.xml"
        cp -f "${conf_src}/hadoop-env.sh" "${hdfs_conf_dir}/hadoop-env.sh"
    fi
}

hydrate_hbase_hadoop_runtime_from_image() {
    local runtime_dir="${ROOT_DIR}/prebuild/hadoop/hadoop-2.10.2"
    local required_conf="${runtime_dir}/etc/hadoop/hdfs-site.xml"
    local image="upfuzz_hdfs:hadoop-2.10.2"
    local parent_dir
    parent_dir="$(dirname "${runtime_dir}")"

    if [[ -f "${required_conf}" ]]; then
        return
    fi

    docker image inspect "${image}" >/dev/null 2>&1 \
        || die "Missing image ${image}. Pull it first (disable --skip-pull or pull manually)."

    log "Hydrating ${runtime_dir} from docker image ${image}"
    mkdir -p "${parent_dir}"
    rm -rf "${runtime_dir}"

    local cid
    cid="$(docker create "${image}")"
    docker cp "${cid}:/hadoop/hadoop-2.10.2" "${runtime_dir}"
    docker rm -f "${cid}" >/dev/null 2>&1 || true

    [[ -f "${required_conf}" ]] || die "Failed to hydrate HBase hadoop runtime: ${required_conf} missing"
}

ensure_hdfs_example_files() {
    local dir="${ROOT_DIR}/examplecase"
    local plan="${dir}/testplan_hdfs_example.txt"
    local valid="${dir}/validcommands_hdfs_example.txt"

    mkdir -p "${dir}"

    if [[ ! -f "${plan}" ]]; then
        cat > "${plan}" <<'EOF'
[Command] Execute {dfs -mkdir /upfuzz_demo}
[Command] Execute {dfs -touchz /upfuzz_demo/a.txt}
[Command] Execute {dfs -ls /upfuzz_demo}
[UpgradeOp] Upgrade Node[0]
[Command] Execute {dfs -ls /upfuzz_demo}
[UpgradeOp] Upgrade Node[1]
[UpgradeOp] Upgrade Node[2]
[Command] Execute {dfs -count -q -h /upfuzz_demo}
EOF
    fi

    if [[ ! -f "${valid}" ]]; then
        cat > "${valid}" <<'EOF'
dfs -ls /upfuzz_demo
EOF
    fi
}

resolve_java11_home() {
    local candidates=(
        "${JAVA11_HOME:-}"
        "/usr/lib/jvm/java-11-openjdk-amd64"
        "/usr/lib/jvm/java-11-openjdk"
    )
    local c
    for c in "${candidates[@]}"; do
        if [[ -n "${c}" && -x "${c}/bin/java" ]]; then
            echo "${c}"
            return 0
        fi
    done
    return 1
}

pull_and_tag_images() {
    local remote_img="${NAMESPACE}/upfuzz_${SYSTEM}:${ORIGINAL_VERSION}_${UPGRADED_VERSION}"
    local local_img="upfuzz_${SYSTEM}:${ORIGINAL_VERSION}_${UPGRADED_VERSION}"

    log "Pulling ${remote_img}"
    docker pull "${remote_img}"
    docker tag "${remote_img}" "${local_img}"

    if [[ "${SYSTEM}" == "hbase" ]]; then
        local dep_remote="${NAMESPACE}/upfuzz_hdfs:hadoop-2.10.2"
        local dep_local="upfuzz_hdfs:hadoop-2.10.2"
        log "Pulling HBase dependency image ${dep_remote}"
        docker pull "${dep_remote}"
        docker tag "${dep_remote}" "${dep_local}"
    fi
}

ensure_bidirectional_image_tags() {
    local forward_img="upfuzz_${SYSTEM}:${ORIGINAL_VERSION}_${UPGRADED_VERSION}"
    local reverse_img="upfuzz_${SYSTEM}:${UPGRADED_VERSION}_${ORIGINAL_VERSION}"

    docker image inspect "${forward_img}" >/dev/null 2>&1 \
        || die "Missing local image tag: ${forward_img}"

    # Differential runs may create one executor with reversed version order.
    # The same local image supports both orderings, so ensure both tags exist.
    docker tag "${forward_img}" "${reverse_img}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --job-id)
            JOB_ID="$2"
            shift 2
            ;;
        --system)
            SYSTEM="$2"
            shift 2
            ;;
        --original)
            ORIGINAL_VERSION="$2"
            shift 2
            ;;
        --upgraded)
            UPGRADED_VERSION="$2"
            shift 2
            ;;
        --run-name)
            RUN_NAME="$2"
            shift 2
            ;;
        --rounds)
            ROUNDS="$2"
            shift 2
            ;;
        --timeout-sec)
            TIMEOUT_SEC="$2"
            shift 2
            ;;
        --clients)
            CLIENTS="$2"
            shift 2
            ;;
        --testing-mode)
            TESTING_MODE="$2"
            shift 2
            ;;
        --node-num)
            NODE_NUM="$2"
            shift 2
            ;;
        --namespace)
            NAMESPACE="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift 1
            ;;
        --skip-pull)
            SKIP_PULL=true
            shift 1
            ;;
        --list-jobs)
            print_jobs
            exit 0
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "Unknown argument: $1"
            ;;
    esac
done

require_cmd docker
require_cmd tar
ensure_docker_compose

if [[ -n "${JOB_ID}" ]]; then
    assign_job "${JOB_ID}"
fi

[[ -n "${SYSTEM}" ]] || die "Missing target system. Provide --job-id or --system/--original/--upgraded."
[[ -n "${ORIGINAL_VERSION}" ]] || die "Missing --original version."
[[ -n "${UPGRADED_VERSION}" ]] || die "Missing --upgraded version."

case "${SYSTEM}" in
    cassandra)
        [[ "${ORIGINAL_VERSION}" == apache-cassandra-* ]] || die "Invalid Cassandra original version: ${ORIGINAL_VERSION}"
        [[ "${UPGRADED_VERSION}" == apache-cassandra-* ]] || die "Invalid Cassandra upgraded version: ${UPGRADED_VERSION}"
        ;;
    hbase)
        [[ "${ORIGINAL_VERSION}" == hbase-* ]] || die "Invalid HBase original version: ${ORIGINAL_VERSION}"
        [[ "${UPGRADED_VERSION}" == hbase-* ]] || die "Invalid HBase upgraded version: ${UPGRADED_VERSION}"
        ;;
    hdfs)
        [[ "${ORIGINAL_VERSION}" == hadoop-* ]] || die "Invalid HDFS original version: ${ORIGINAL_VERSION}"
        [[ "${UPGRADED_VERSION}" == hadoop-* ]] || die "Invalid HDFS upgraded version: ${UPGRADED_VERSION}"
        ;;
    *)
        die "Unsupported system: ${SYSTEM}"
        ;;
esac

if [[ -z "${RUN_NAME}" ]]; then
    RUN_NAME="${SYSTEM}_${ORIGINAL_VERSION}_to_${UPGRADED_VERSION}_cloudlab_$(date '+%Y%m%d_%H%M%S')"
fi

LAUNCH_DIR="${RESULTS_ROOT}/${RUN_NAME}"
mkdir -p "${LAUNCH_DIR}"
LAUNCH_LOG="${LAUNCH_DIR}/launch.log"

log "Job setup: ${SYSTEM} ${ORIGINAL_VERSION} -> ${UPGRADED_VERSION}" | tee -a "${LAUNCH_LOG}"
log "Namespace: ${NAMESPACE}" | tee -a "${LAUNCH_LOG}"

ensure_prebuild_materialized "${SYSTEM}" "${ORIGINAL_VERSION}"
ensure_prebuild_materialized "${SYSTEM}" "${UPGRADED_VERSION}"
if [[ "${SYSTEM}" == "hbase" ]]; then
    ensure_prebuild_materialized "hdfs" "hadoop-2.10.2"
    ensure_hbase_hadoop_alias
    refresh_hbase_hadoop_runtime_config
fi
if [[ "${SYSTEM}" == "hdfs" ]]; then
    ensure_hdfs_tmp_root_writable
    ensure_hdfs_example_files
fi

if [[ "${SKIP_BUILD}" == false ]]; then
    JAVA11_BUILD_HOME="$(resolve_java11_home)" || die "Java 11 not found. Install openjdk-11-jdk or set JAVA11_HOME."
    log "Preparing runtime dependencies (./gradlew copyDependencies)" | tee -a "${LAUNCH_LOG}"
    (
        cd "${ROOT_DIR}"
        JAVA_HOME="${JAVA11_BUILD_HOME}" PATH="${JAVA11_BUILD_HOME}/bin:${PATH}" ./gradlew copyDependencies
    ) 2>&1 | tee -a "${LAUNCH_LOG}"

    log "Building Java classes (./gradlew classes -x test)" | tee -a "${LAUNCH_LOG}"
    (
        cd "${ROOT_DIR}"
        JAVA_HOME="${JAVA11_BUILD_HOME}" PATH="${JAVA11_BUILD_HOME}/bin:${PATH}" ./gradlew classes -x test
    ) 2>&1 | tee -a "${LAUNCH_LOG}"
else
    log "Skipping Java build (--skip-build)" | tee -a "${LAUNCH_LOG}"
fi

if [[ "${SKIP_PULL}" == false ]]; then
    pull_and_tag_images 2>&1 | tee -a "${LAUNCH_LOG}"
else
    log "Skipping docker pull/tag (--skip-pull)" | tee -a "${LAUNCH_LOG}"
fi

ensure_bidirectional_image_tags

if [[ "${SYSTEM}" == "hbase" ]]; then
    hydrate_hbase_hadoop_runtime_from_image
fi

RUNNER_CMD=(
    "${RUNNER_SCRIPT}"
    --system "${SYSTEM}"
    --original "${ORIGINAL_VERSION}"
    --upgraded "${UPGRADED_VERSION}"
    --rounds "${ROUNDS}"
    --timeout-sec "${TIMEOUT_SEC}"
    --clients "${CLIENTS}"
    --testing-mode "${TESTING_MODE}"
    --use-trace true
    --print-trace true
    --require-trace-signal
    --run-name "${RUN_NAME}"
)
if [[ -n "${NODE_NUM}" ]]; then
    RUNNER_CMD+=(--node-num "${NODE_NUM}")
fi

log "Launching: ${RUNNER_CMD[*]}" | tee -a "${LAUNCH_LOG}"
(
    cd "${ROOT_DIR}"
    "${RUNNER_CMD[@]}"
) 2>&1 | tee -a "${LAUNCH_LOG}"

RUNNER_RESULT_DIR="${ROOT_DIR}/scripts/runner/results/${RUN_NAME}"
SUMMARY_FILE="${RUNNER_RESULT_DIR}/summary.txt"

if [[ -f "${SUMMARY_FILE}" ]]; then
    cp -f "${SUMMARY_FILE}" "${LAUNCH_DIR}/summary.txt"
    for f in config.json server_stdout.log client_launcher_stdout.log upfuzz_server.log upfuzz_client_1.log monitor.log; do
        [[ -f "${RUNNER_RESULT_DIR}/${f}" ]] && cp -f "${RUNNER_RESULT_DIR}/${f}" "${LAUNCH_DIR}/${f}"
    done

    log "Run complete. Summary: ${SUMMARY_FILE}" | tee -a "${LAUNCH_LOG}"
    egrep "^(system:|original_version:|upgraded_version:|observed_rounds:|diff_feedback_packets:|stop_reason:|trace_signal_ok:|trace_len_positive_count:|trace_len_zero_count:|trace_merged_old_nonzero_count:|trace_merged_rolling_nonzero_count:|trace_merged_new_nonzero_count:|trace_merged_zero_count:|message_tri_diff_count:|trace_connect_refused_count:)" \
        "${SUMMARY_FILE}" | tee -a "${LAUNCH_LOG}"
else
    die "Runner finished without summary file: ${SUMMARY_FILE}"
fi
