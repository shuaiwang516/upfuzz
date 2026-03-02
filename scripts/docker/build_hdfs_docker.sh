#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
UPFUZZ_DIR="${UPFUZZ_DIR:-${ROOT_DIR}}"
PREBUILD_MIRROR_URL="${PREBUILD_MIRROR_URL:-https://mir.cs.illinois.edu/~swang516/rupfuzz/prebuild/}"
FORCE_DOCKER_REBUILD="${FORCE_DOCKER_REBUILD:-1}"
JAVA8_HOME="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"

usage() {
    cat <<'USAGE'
Usage:
  build_hdfs_docker.sh <originalVersion> <upgradedVersion>

Example:
  build_hdfs_docker.sh hadoop-2.10.2 hadoop-3.3.6

Notes:
  - Follows docs/RUN.md upgrade-testing docker build prep for HDFS.
  - The only download change is using the prebuild mirror URL.

Env:
  UPFUZZ_DIR            Repo root (default: script-detected root)
  PREBUILD_MIRROR_URL   Mirror URL for prebuild directory
  JAVA8_HOME            Java 8 home for daemon javac step
  FORCE_DOCKER_REBUILD  Force docker rebuild (default: 1)
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
    command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

lower() {
    echo "$1" | tr '[:upper:]' '[:lower:]'
}

is_truthy() {
    case "$(lower "$1")" in
        1|true|yes|y|on) return 0 ;;
        *) return 1 ;;
    esac
}

version_number() {
    local v="$1"
    echo "${v#hadoop-}"
}

major_version() {
    local num
    num="$(version_number "$1")"
    echo "${num%%.*}"
}

download_prebuild_tarball_if_needed() {
    local mirror_subdir="$1"
    local version="$2"
    local dest_archive="$3"
    local url="${PREBUILD_MIRROR_URL%/}/${mirror_subdir}/${version}.tar.gz"

    if [[ -f "${dest_archive}" ]]; then
        return
    fi

    require_cmd wget
    mkdir -p "$(dirname "${dest_archive}")"
    log "Downloading tarball: ${url}"
    wget -O "${dest_archive}" "${url}"
}

extract_prebuild_version_if_needed() {
    local version="$1"
    local base_dir="${UPFUZZ_DIR}/prebuild/hdfs"
    local target_dir="${base_dir}/${version}"
    local src_dir="${base_dir}/${version}-src"
    local archive="${base_dir}/${version}.tar.gz"

    if [[ -d "${target_dir}" ]]; then
        return
    fi

    download_prebuild_tarball_if_needed "hdfs" "${version}" "${archive}"
    [[ -f "${archive}" ]] || die "Missing archive: ${archive}"

    tar -xzf "${archive}" -C "${base_dir}"

    if [[ -d "${src_dir}" && ! -d "${target_dir}" ]]; then
        mv "${src_dir}" "${target_dir}"
    fi

    if [[ ! -d "${target_dir}" ]]; then
        local extracted_root
        extracted_root="$(tar -tzf "${archive}" | head -n1 | cut -d/ -f1)"
        if [[ -n "${extracted_root}" && -d "${base_dir}/${extracted_root}" ]]; then
            mv "${base_dir}/${extracted_root}" "${target_dir}"
        fi
    fi

    [[ -d "${target_dir}" ]] || die "Cannot extract HDFS prebuild dir for ${version}"
}

patch_hdfs_daemon_for_version() {
    local version="$1"
    local version_dir="${UPFUZZ_DIR}/prebuild/hdfs/${version}"

    if (( $(major_version "${version}") == 2 )); then
        cp -f "${UPFUZZ_DIR}/src/main/resources/FsShellDaemon2.java" \
            "${version_dir}/FsShellDaemon.java"
        (
            cd "${version_dir}"
            "${JAVA8_HOME}/bin/javac" -d . -cp "share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*" FsShellDaemon.java
        )
        if ! grep -q "dfsdaemon" "${version_dir}/bin/hdfs"; then
            sed -i 's/elif \[ "\$COMMAND" = "dfs" \] ; then/elif [ "\$COMMAND" = "dfsdaemon" ] ; then\n  CLASS=org.apache.hadoop.fs.FsShellDaemon\n  HADOOP_OPTS="\$HADOOP_OPTS \$HADOOP_CLIENT_OPTS"\n&/' \
                "${version_dir}/bin/hdfs"
        fi
    else
        cp -f "${UPFUZZ_DIR}/src/main/resources/FsShellDaemon_trunk.java" \
            "${version_dir}/FsShellDaemon.java"
        (
            cd "${version_dir}"
            "${JAVA8_HOME}/bin/javac" -d . -cp "share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*" FsShellDaemon.java
        )
        if ! grep -q "dfsdaemon" "${version_dir}/bin/hdfs"; then
            sed -i 's/  case ${subcmd} in/&\n    dfsdaemon)\n      HADOOP_CLASSNAME="org.apache.hadoop.fs.FsShellDaemon"\n    ;;/' \
                "${version_dir}/bin/hdfs"
        fi
    fi
}

prepare_hdfs_upgrade_build_context() {
    local ori_version="$1"
    local up_version="$2"

    extract_prebuild_version_if_needed "${ori_version}"
    extract_prebuild_version_if_needed "${up_version}"

    patch_hdfs_daemon_for_version "${ori_version}"
    patch_hdfs_daemon_for_version "${up_version}"

    sed -i "s/ORI_VERSION=hadoop-.*$/ORI_VERSION=${ori_version}/" \
        "${UPFUZZ_DIR}/src/main/resources/hdfs/compile-src/hdfs-clusternode.sh"
    sed -i "s/UPG_VERSION=hadoop-.*$/UPG_VERSION=${up_version}/" \
        "${UPFUZZ_DIR}/src/main/resources/hdfs/compile-src/hdfs-clusternode.sh"
}

main() {
    [[ $# -eq 2 ]] || {
        usage
        exit 1
    }

    require_cmd docker
    require_cmd sed
    require_cmd tar
    require_cmd grep

    local ori_version="$1"
    local up_version="$2"
    [[ "${ori_version}" == hadoop-* ]] || die "original version must start with hadoop-"
    [[ "${up_version}" == hadoop-* ]] || die "upgraded version must start with hadoop-"

    prepare_hdfs_upgrade_build_context "${ori_version}" "${up_version}"

    local docker_args=()
    if is_truthy "${FORCE_DOCKER_REBUILD}"; then
        docker_args=(--no-cache --pull)
    fi

    (
        cd "${UPFUZZ_DIR}/src/main/resources/hdfs/compile-src"
        docker build "${docker_args[@]}" . -t "upfuzz_hdfs:${ori_version}_${up_version}"
    )
}

main "$@"
