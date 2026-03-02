#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
UPFUZZ_DIR="${UPFUZZ_DIR:-${ROOT_DIR}}"
FORCE_DOCKER_REBUILD="${FORCE_DOCKER_REBUILD:-1}"
PREBUILD_MIRROR_URL="${PREBUILD_MIRROR_URL:-https://mir.cs.illinois.edu/~swang516/rupfuzz/prebuild/}"

usage() {
    cat <<'USAGE'
Usage:
  build_rolling_image_pair.sh <system> <originalVersion> <upgradedVersion>

Examples:
  build_rolling_image_pair.sh cassandra apache-cassandra-4.1.9 apache-cassandra-5.0.4
  build_rolling_image_pair.sh hdfs hadoop-2.10.2 hadoop-3.3.6
  build_rolling_image_pair.sh hbase hbase-2.4.18 hbase-2.5.9

Env:
  UPFUZZ_DIR            Repo root (default: script-detected root)
  FORCE_DOCKER_REBUILD  Force docker rebuild (default: 1)
  PREBUILD_MIRROR_URL   Mirror URL for prebuild directory
USAGE
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

verify_args() {
    local system="$1"
    local original="$2"
    local upgraded="$3"

    case "${system}" in
        cassandra)
            [[ "${original}" == apache-cassandra-* ]] || die "Cassandra original version must start with apache-cassandra-"
            [[ "${upgraded}" == apache-cassandra-* ]] || die "Cassandra upgraded version must start with apache-cassandra-"
            ;;
        hdfs)
            [[ "${original}" == hadoop-* ]] || die "HDFS original version must start with hadoop-"
            [[ "${upgraded}" == hadoop-* ]] || die "HDFS upgraded version must start with hadoop-"
            ;;
        hbase)
            [[ "${original}" == hbase-* ]] || die "HBase original version must start with hbase-"
            [[ "${upgraded}" == hbase-* ]] || die "HBase upgraded version must start with hbase-"
            ;;
        *)
            die "Unsupported system: ${system}"
            ;;
    esac
}

main() {
    [[ $# -eq 3 ]] || {
        usage
        exit 1
    }

    local system="$1"
    local original="$2"
    local upgraded="$3"

    verify_args "${system}" "${original}" "${upgraded}"

    local builder_script=""
    case "${system}" in
        cassandra) builder_script="${SCRIPT_DIR}/build_cassandra_docker.sh" ;;
        hdfs) builder_script="${SCRIPT_DIR}/build_hdfs_docker.sh" ;;
        hbase) builder_script="${SCRIPT_DIR}/build_hbase_docker.sh" ;;
    esac

    [[ -x "${builder_script}" ]] || die "Builder script is not executable: ${builder_script}"

    env \
        UPFUZZ_DIR="${UPFUZZ_DIR}" \
        FORCE_DOCKER_REBUILD="${FORCE_DOCKER_REBUILD}" \
        PREBUILD_MIRROR_URL="${PREBUILD_MIRROR_URL}" \
        JAVA8_HOME="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}" \
        "${builder_script}" "${original}" "${upgraded}"
}

main "$@"
