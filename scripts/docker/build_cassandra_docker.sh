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
  build_cassandra_docker.sh <originalVersion> <upgradedVersion>

Example:
  build_cassandra_docker.sh apache-cassandra-4.1.9 apache-cassandra-5.0.4

Notes:
  - Follows docs/RUN.md upgrade-testing docker build prep for Cassandra.
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
    echo "${v#apache-cassandra-}"
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
    local base_dir="${UPFUZZ_DIR}/prebuild/cassandra"
    local target_dir="${base_dir}/${version}"
    local src_dir="${base_dir}/${version}-src"
    local archive="${base_dir}/${version}.tar.gz"

    if [[ -d "${target_dir}" ]]; then
        return
    fi

    download_prebuild_tarball_if_needed "cassandra" "${version}" "${archive}"
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

    [[ -d "${target_dir}" ]] || die "Cannot extract Cassandra prebuild dir for ${version}"
}

pick_cqlsh_daemon_file() {
    local version="$1"
    local major
    major="$(major_version "${version}")"

    if (( major >= 5 )); then
        echo "cqlsh_daemon5.py"
    elif (( major >= 4 )); then
        echo "cqlsh_daemon4.py"
    else
        echo "cqlsh_daemon2.py"
    fi
}

prepare_cassandra_upgrade_build_context() {
    local ori_version="$1"
    local up_version="$2"
    local ori_daemon
    local up_daemon

    extract_prebuild_version_if_needed "${ori_version}"
    extract_prebuild_version_if_needed "${up_version}"

    ori_daemon="$(pick_cqlsh_daemon_file "${ori_version}")"
    up_daemon="$(pick_cqlsh_daemon_file "${up_version}")"

    cp -f "${UPFUZZ_DIR}/src/main/resources/${ori_daemon}" \
        "${UPFUZZ_DIR}/prebuild/cassandra/${ori_version}/bin/cqlsh_daemon.py"
    cp -f "${UPFUZZ_DIR}/src/main/resources/${up_daemon}" \
        "${UPFUZZ_DIR}/prebuild/cassandra/${up_version}/bin/cqlsh_daemon.py"

    if (( $(major_version "${ori_version}") == 3 && $(major_version "${up_version}") == 4 )); then
        sed -i 's/num_tokens: 16/num_tokens: 256/' \
            "${UPFUZZ_DIR}/prebuild/cassandra/${up_version}/conf/cassandra.yaml" || true
    fi

    sed -i "s/ORI_VERSION=apache-cassandra-.*$/ORI_VERSION=${ori_version}/" \
        "${UPFUZZ_DIR}/src/main/resources/cassandra/upgrade-testing/compile-src/cassandra-clusternode.sh"
    sed -i "s/UP_VERSION=apache-cassandra-.*$/UP_VERSION=${up_version}/" \
        "${UPFUZZ_DIR}/src/main/resources/cassandra/upgrade-testing/compile-src/cassandra-clusternode.sh"
}

main() {
    [[ $# -eq 2 ]] || {
        usage
        exit 1
    }

    require_cmd docker
    require_cmd sed
    require_cmd tar

    local ori_version="$1"
    local up_version="$2"
    [[ "${ori_version}" == apache-cassandra-* ]] || die "original version must start with apache-cassandra-"
    [[ "${up_version}" == apache-cassandra-* ]] || die "upgraded version must start with apache-cassandra-"

    prepare_cassandra_upgrade_build_context "${ori_version}" "${up_version}"

    if is_truthy "${SKIP_DOCKER_BUILD:-0}"; then
        log "SKIP_DOCKER_BUILD set — skipping docker build"
        return
    fi

    local docker_args=()
    if is_truthy "${FORCE_DOCKER_REBUILD}"; then
        docker_args=(--no-cache --pull)
    fi

    (
        cd "${UPFUZZ_DIR}/src/main/resources/cassandra/upgrade-testing/compile-src"
        docker build "${docker_args[@]}" . -t "upfuzz_cassandra:${ori_version}_${up_version}"
    )
}

main "$@"
