#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
UPFUZZ_DIR="${UPFUZZ_DIR:-${ROOT_DIR}}"
PREBUILD_MIRROR_URL="${PREBUILD_MIRROR_URL:-https://mir.cs.illinois.edu/~swang516/rupfuzz/prebuild/}"
FORCE_DOCKER_REBUILD="${FORCE_DOCKER_REBUILD:-1}"

usage() {
    cat <<'USAGE'
Usage:
  build_hbase_docker.sh <originalVersion> <upgradedVersion>

Example:
  build_hbase_docker.sh hbase-2.4.18 hbase-2.5.9

Notes:
  - Follows docs/RUN.md upgrade-testing docker build prep for HBase.
  - The only download change is using the prebuild mirror URL.

Env:
  UPFUZZ_DIR            Repo root (default: script-detected root)
  PREBUILD_MIRROR_URL   Mirror URL for prebuild directory
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
    v="${v#hbase-}"
    echo "${v%%-*}"
}

major_version() {
    local num
    num="$(version_number "$1")"
    echo "${num%%.*}"
}

minor_version() {
    local num rest
    num="$(version_number "$1")"
    rest="${num#*.}"
    echo "${rest%%.*}"
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
    local system_dir="$1"
    local version="$2"
    local mirror_subdir="$3"

    local target_dir="${system_dir}/${version}"
    local src_dir="${system_dir}/${version}-src"
    local archive_tar="${system_dir}/${version}.tar.gz"
    local archive="${archive_tar}"

    if [[ -d "${target_dir}" ]]; then
        return
    fi

    download_prebuild_tarball_if_needed "${mirror_subdir}" "${version}" "${archive}"
    [[ -f "${archive}" ]] || die "Missing archive for ${version} under ${system_dir}"

    tar -xzf "${archive}" -C "${system_dir}"

    if [[ -d "${src_dir}" && ! -d "${target_dir}" ]]; then
        mv "${src_dir}" "${target_dir}"
    fi

    if [[ ! -d "${target_dir}" ]]; then
        local extracted_root
        extracted_root="$(tar -tzf "${archive}" | head -n1 | cut -d/ -f1)"
        if [[ -n "${extracted_root}" && -d "${system_dir}/${extracted_root}" ]]; then
            if [[ "${extracted_root}" != "${version}" ]]; then
                mv "${system_dir}/${extracted_root}" "${target_dir}"
            fi
        fi
    fi

    [[ -d "${target_dir}" ]] || die "Cannot extract prebuild dir for ${version} under ${system_dir}"
}

ensure_hadoop_2102_for_hbase() {
    local hadoop_dir="${UPFUZZ_DIR}/prebuild/hadoop"
    local dep_version="hadoop-2.10.2"
    local dep_dir="${hadoop_dir}/${dep_version}"

    if [[ ! -d "${dep_dir}" ]]; then
        extract_prebuild_version_if_needed "${hadoop_dir}" "${dep_version}" "hadoop"
    fi

    [[ -d "${dep_dir}" ]] || die "Missing hadoop dependency dir: ${dep_dir}"

    cp -f "${UPFUZZ_DIR}/src/main/resources/hdfs/hbase-pure/core-site.xml" \
        "${dep_dir}/etc/hadoop/core-site.xml"
    cp -f "${UPFUZZ_DIR}/src/main/resources/hdfs/hbase-pure/hdfs-site.xml" \
        "${dep_dir}/etc/hadoop/hdfs-site.xml"
    cp -f "${UPFUZZ_DIR}/src/main/resources/hdfs/hbase-pure/hadoop-env.sh" \
        "${dep_dir}/etc/hadoop/hadoop-env.sh"
}

pick_hbase_daemon_file() {
    local version="$1"
    local major minor
    major="$(major_version "${version}")"
    minor="$(minor_version "${version}")"

    if (( major > 2 || (major == 2 && minor >= 4) )); then
        echo "hbase_daemon3.py"
    else
        echo "hbase_daemon2.py"
    fi
}

prepare_hbase_upgrade_build_context() {
    local ori_version="$1"
    local up_version="$2"
    local ori_daemon
    local up_daemon

    ensure_hadoop_2102_for_hbase

    extract_prebuild_version_if_needed "${UPFUZZ_DIR}/prebuild/hbase" "${ori_version}" "hbase"
    extract_prebuild_version_if_needed "${UPFUZZ_DIR}/prebuild/hbase" "${up_version}" "hbase"

    cp -f "${UPFUZZ_DIR}/src/main/resources/hbase/compile-src/hbase-env.sh" \
        "${UPFUZZ_DIR}/prebuild/hbase/${ori_version}/conf/hbase-env.sh"
    cp -f "${UPFUZZ_DIR}/src/main/resources/hbase/compile-src/hbase-env.sh" \
        "${UPFUZZ_DIR}/prebuild/hbase/${up_version}/conf/hbase-env.sh"

    if (( $(major_version "${up_version}") >= 3 )); then
        cp -f "${UPFUZZ_DIR}/src/main/resources/hbase/compile-src/hbase-env-jdk17.sh" \
            "${UPFUZZ_DIR}/prebuild/hbase/${up_version}/conf/hbase-env.sh"
    fi

    ori_daemon="$(pick_hbase_daemon_file "${ori_version}")"
    up_daemon="$(pick_hbase_daemon_file "${up_version}")"

    cp -f "${UPFUZZ_DIR}/src/main/resources/hbase/compile-src/${ori_daemon}" \
        "${UPFUZZ_DIR}/prebuild/hbase/${ori_version}/bin/hbase_daemon.py"
    cp -f "${UPFUZZ_DIR}/src/main/resources/hbase/compile-src/${up_daemon}" \
        "${UPFUZZ_DIR}/prebuild/hbase/${up_version}/bin/hbase_daemon.py"
}

main() {
    [[ $# -eq 2 ]] || {
        usage
        exit 1
    }

    require_cmd docker
    require_cmd tar

    local ori_version="$1"
    local up_version="$2"
    [[ "${ori_version}" == hbase-* ]] || die "original version must start with hbase-"
    [[ "${up_version}" == hbase-* ]] || die "upgraded version must start with hbase-"

    prepare_hbase_upgrade_build_context "${ori_version}" "${up_version}"

    local docker_args=()
    if is_truthy "${FORCE_DOCKER_REBUILD}"; then
        docker_args=(--no-cache --pull)
    fi

    (
        cd "${UPFUZZ_DIR}/src/main/resources/hdfs/hbase-pure"
        docker build "${docker_args[@]}" . -t "upfuzz_hdfs:hadoop-2.10.2"
    )

    (
        cd "${UPFUZZ_DIR}/src/main/resources/hbase/compile-src"
        docker build "${docker_args[@]}" . -t "upfuzz_hbase:${ori_version}_${up_version}"
    )
}

main "$@"
