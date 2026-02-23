#!/usr/bin/env bash
set -euo pipefail

TARGET_USER="${SUDO_USER:-${USER}}"
TARGET_HOME="$(getent passwd "${TARGET_USER}" | cut -d: -f6 || true)"
if [[ -z "${TARGET_HOME}" ]]; then
    TARGET_HOME="${HOME}"
fi

WORKSPACE_ROOT="${TARGET_HOME}/xlab/rupfuzz"
UPFUZZ_REPO_URL="https://github.com/shuaiwang516/upfuzz.git"
UPFUZZ_BRANCH="rupfuzz"
UPFUZZ_DIR=""
SSG_RUNTIME_REPO_URL="https://github.com/shuaiwang516/ssg-runtime.git"
SSG_RUNTIME_BRANCH="rupfuzz"
SSG_RUNTIME_DIR=""

PREBUILD_SOURCE_DIR=""
PULL_IMAGES=false
IMAGE_PREFIX=""
SKIP_BUILD=false
SKIP_PREBUILD_CHECK=false
SKIP_IMAGE_CHECK=false

usage() {
    cat <<EOF
Usage:
  $(basename "$0") [options]

Options:
  --workspace-root <dir>       Workspace root (default: ${WORKSPACE_ROOT})
  --upfuzz-dir <dir>           UpFuzz directory (default: <workspace-root>/upfuzz)
  --ssg-runtime-dir <dir>      ssg-runtime directory (default: <workspace-root>/ssg-runtime)
  --prebuild-source-dir <dir>  Optional source dir to copy required prebuild archives from
  --pull-images                Pull expected images from registry prefix and tag locally
  --image-prefix <prefix>      Registry/org prefix for pull, e.g. shuaiwang516
  --skip-build                 Skip ./gradlew build steps for ssg-runtime and upfuzz
  --skip-prebuild-check        Skip required prebuild archive checks
  --skip-image-check           Skip docker image checks
  -h, --help                   Show this help

Examples:
  $(basename "$0")
  $(basename "$0") --workspace-root /mydata/rupfuzz
  $(basename "$0") --pull-images --image-prefix shuaiwang516
EOF
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

run_as_root() {
    if [[ "$(id -u)" -eq 0 ]]; then
        "$@"
    else
        sudo "$@"
    fi
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --workspace-root)
            WORKSPACE_ROOT="$2"
            shift 2
            ;;
        --upfuzz-dir)
            UPFUZZ_DIR="$2"
            shift 2
            ;;
        --ssg-runtime-dir)
            SSG_RUNTIME_DIR="$2"
            shift 2
            ;;
        --prebuild-source-dir)
            PREBUILD_SOURCE_DIR="$2"
            shift 2
            ;;
        --pull-images)
            PULL_IMAGES=true
            shift
            ;;
        --image-prefix)
            IMAGE_PREFIX="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-prebuild-check)
            SKIP_PREBUILD_CHECK=true
            shift
            ;;
        --skip-image-check)
            SKIP_IMAGE_CHECK=true
            shift
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

if [[ "${PULL_IMAGES}" == true && -z "${IMAGE_PREFIX}" ]]; then
    die "--pull-images requires --image-prefix"
fi

if [[ -z "${UPFUZZ_DIR}" ]]; then
    UPFUZZ_DIR="${WORKSPACE_ROOT}/upfuzz"
fi
if [[ -z "${SSG_RUNTIME_DIR}" ]]; then
    SSG_RUNTIME_DIR="${WORKSPACE_ROOT}/ssg-runtime"
fi

PREBUILD_REQUIRED=(
    "prebuild/cassandra/apache-cassandra-3.11.19-src-instrumented.tar.gz"
    "prebuild/cassandra/apache-cassandra-4.1.10-src-instrumented.tar.gz"
    "prebuild/cassandra/apache-cassandra-5.0.6-src-instrumented.tar.gz"
    "prebuild/hdfs/hadoop-2.10.2-src-instrumented.tar.gz"
    "prebuild/hdfs/hadoop-3.3.6-src-instrumented.tar.gz"
    "prebuild/hdfs/hadoop-3.4.2-src-instrumented.tar.gz"
    "prebuild/hbase/hbase-2.5.13-src-instrumented.tar.gz"
    "prebuild/hbase/hbase-2.6.4-src-instrumented.tar.gz"
    "prebuild/hbase/hbase-3.0.0-beta-1-src-instrumented.tar.gz"
)

IMAGE_REQUIRED=(
    "upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6"
    "upfuzz_hbase:hbase-2.5.13_hbase-2.6.4"
    "upfuzz_hbase:hbase-2.6.4_hbase-3.0.0-beta-1"
    "upfuzz_hdfs:hadoop-2.10.2"
    "upfuzz_hdfs:hadoop-2.10.2_hadoop-3.3.6"
    "upfuzz_hdfs:hadoop-3.3.6_hadoop-3.4.2"
)

ensure_ubuntu_22() {
    if [[ -f /etc/os-release ]]; then
        # shellcheck disable=SC1091
        . /etc/os-release
        if [[ "${ID:-}" != "ubuntu" ]]; then
            die "This script targets Ubuntu. Current OS: ${ID:-unknown}"
        fi
        if [[ "${VERSION_ID:-}" != "22.04" ]]; then
            log "WARNING: Expected Ubuntu 22.04, found ${VERSION_ID:-unknown}. Continuing."
        fi
    else
        die "/etc/os-release not found; cannot validate OS"
    fi
}

install_base_packages() {
    log "Installing base packages"
    run_as_root apt-get update
    run_as_root apt-get install -y \
        git curl wget ca-certificates gnupg lsb-release software-properties-common apt-transport-https \
        unzip zip tmux build-essential \
        ripgrep iproute2 ant maven
}

install_java8_with_fallback() {
    if dpkg -s openjdk-8-jdk >/dev/null 2>&1; then
        log "openjdk-8-jdk already installed"
        return
    fi

    if apt-cache show openjdk-8-jdk >/dev/null 2>&1; then
        log "Installing openjdk-8-jdk from apt"
        run_as_root apt-get install -y openjdk-8-jdk
        return
    fi

    log "openjdk-8-jdk not available via apt; installing temurin-8-jdk"
    run_as_root install -m 0755 -d /etc/apt/keyrings
    if [[ ! -f /etc/apt/keyrings/adoptium.gpg ]]; then
        curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | run_as_root gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
    fi
    if [[ ! -f /etc/apt/sources.list.d/adoptium.list ]]; then
        echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | run_as_root tee /etc/apt/sources.list.d/adoptium.list >/dev/null
    fi
    run_as_root apt-get update
    run_as_root apt-get install -y temurin-8-jdk
}

install_java_toolchains() {
    log "Installing Java toolchains (8/11/17)"
    install_java8_with_fallback
    run_as_root apt-get install -y openjdk-11-jdk openjdk-17-jdk

    if [[ ! -d /usr/lib/jvm/java-8-openjdk-amd64 ]]; then
        for candidate in /usr/lib/jvm/temurin-8-jdk-amd64 /usr/lib/jvm/temurin-8-jdk; do
            if [[ -d "${candidate}" ]]; then
                log "Creating compatibility symlink /usr/lib/jvm/java-8-openjdk-amd64 -> ${candidate}"
                run_as_root ln -s "${candidate}" /usr/lib/jvm/java-8-openjdk-amd64
                break
            fi
        done
    fi
}

install_docker() {
    if command -v docker >/dev/null 2>&1; then
        log "Docker already installed; ensuring daemon is enabled"
        run_as_root systemctl enable --now docker
    else
        log "Installing Docker Engine"
        run_as_root install -m 0755 -d /etc/apt/keyrings
        if [[ ! -f /etc/apt/keyrings/docker.gpg ]]; then
            curl -fsSL https://download.docker.com/linux/ubuntu/gpg | run_as_root gpg --dearmor -o /etc/apt/keyrings/docker.gpg
            run_as_root chmod a+r /etc/apt/keyrings/docker.gpg
        fi

        if [[ ! -f /etc/apt/sources.list.d/docker.list ]]; then
            echo \
                "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
                $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" | \
                run_as_root tee /etc/apt/sources.list.d/docker.list >/dev/null
        fi

        run_as_root apt-get update
        run_as_root apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
        run_as_root systemctl enable --now docker
    fi

    if getent group docker >/dev/null 2>&1; then
        run_as_root usermod -aG docker "${TARGET_USER}"
    fi
}

clone_or_update_repo() {
    local url="$1"
    local branch="$2"
    local dir="$3"

    mkdir -p "$(dirname "${dir}")"
    if [[ -d "${dir}/.git" ]]; then
        log "Updating repo: ${dir}"
        git -C "${dir}" fetch --all --tags
    else
        log "Cloning repo: ${url} -> ${dir}"
        git clone "${url}" "${dir}"
    fi

    git -C "${dir}" checkout "${branch}"
    git -C "${dir}" pull --ff-only origin "${branch}"
}

build_ssg_runtime_and_copy_jar() {
    if [[ "${SKIP_BUILD}" == true ]]; then
        log "Skipping ssg-runtime build (--skip-build)"
        return
    fi

    log "Building ssg-runtime fat jar"
    chmod +x "${SSG_RUNTIME_DIR}/gradlew"
    (
        cd "${SSG_RUNTIME_DIR}"
        ./gradlew --no-daemon fatJar
    )

    [[ -f "${SSG_RUNTIME_DIR}/build/libs/ssgFatJar.jar" ]] || die "Missing ssgFatJar.jar after build"

    mkdir -p "${UPFUZZ_DIR}/lib"
    cp -f "${SSG_RUNTIME_DIR}/build/libs/ssgFatJar.jar" "${UPFUZZ_DIR}/lib/ssgFatJar.jar"
    log "Copied runtime jar to ${UPFUZZ_DIR}/lib/ssgFatJar.jar"
}

build_upfuzz() {
    if [[ "${SKIP_BUILD}" == true ]]; then
        log "Skipping upfuzz build (--skip-build)"
        return
    fi

    log "Building upfuzz dependencies and main artifacts"
    chmod +x "${UPFUZZ_DIR}/gradlew"
    (
        cd "${UPFUZZ_DIR}"
        ./gradlew --no-daemon copyDependencies
        ./gradlew --no-daemon build -x test
    )
}

copy_prebuild_archives_if_requested() {
    [[ -n "${PREBUILD_SOURCE_DIR}" ]] || return
    log "Attempting to copy required prebuild archives from ${PREBUILD_SOURCE_DIR}"

    local rel src1 src2 dst
    for rel in "${PREBUILD_REQUIRED[@]}"; do
        dst="${UPFUZZ_DIR}/${rel}"
        [[ -f "${dst}" ]] && continue

        src1="${PREBUILD_SOURCE_DIR}/${rel}"
        src2="${PREBUILD_SOURCE_DIR}/$(basename "${rel}")"
        mkdir -p "$(dirname "${dst}")"

        if [[ -f "${src1}" ]]; then
            cp -f "${src1}" "${dst}"
        elif [[ -f "${src2}" ]]; then
            cp -f "${src2}" "${dst}"
        fi
    done
}

check_prebuild_archives() {
    if [[ "${SKIP_PREBUILD_CHECK}" == true ]]; then
        log "Skipping prebuild archive check (--skip-prebuild-check)"
        return
    fi

    log "Checking required prebuild archives"
    local missing=()
    local rel
    for rel in "${PREBUILD_REQUIRED[@]}"; do
        if [[ ! -f "${UPFUZZ_DIR}/${rel}" ]]; then
            missing+=("${rel}")
        fi
    done

    if (( ${#missing[@]} > 0 )); then
        echo "Missing required prebuild archives under ${UPFUZZ_DIR}:" >&2
        printf '  - %s\n' "${missing[@]}" >&2
        echo "Provide these files and re-run, or use --prebuild-source-dir." >&2
        exit 2
    fi
}

docker_cmd() {
    if docker info >/dev/null 2>&1; then
        docker "$@"
    else
        run_as_root docker "$@"
    fi
}

pull_or_check_images() {
    if [[ "${SKIP_IMAGE_CHECK}" == true ]]; then
        log "Skipping docker image checks (--skip-image-check)"
        return
    fi

    log "Checking required docker images"
    local image remote missing=()
    for image in "${IMAGE_REQUIRED[@]}"; do
        if docker_cmd image inspect "${image}" >/dev/null 2>&1; then
            continue
        fi

        if [[ "${PULL_IMAGES}" == true ]]; then
            remote="${IMAGE_PREFIX}/${image}"
            log "Pulling ${remote}"
            docker_cmd pull "${remote}"
            docker_cmd tag "${remote}" "${image}"
        else
            missing+=("${image}")
        fi
    done

    if (( ${#missing[@]} > 0 )); then
        echo "WARNING: Missing docker images:" >&2
        printf '  - %s\n' "${missing[@]}" >&2
        echo "You can re-run with --pull-images --image-prefix <prefix> to auto-pull and tag." >&2
    fi
}

write_env_hint() {
    local hint_file="${WORKSPACE_ROOT}/env_upfuzz.sh"
    cat > "${hint_file}" <<EOF
export WORKSPACE_ROOT="${WORKSPACE_ROOT}"
export UPFUZZ_DIR="${UPFUZZ_DIR}"
export SSG_RUNTIME_DIR="${SSG_RUNTIME_DIR}"
EOF
    log "Wrote environment hint file: ${hint_file}"
}

print_versions() {
    log "Version summary"
    java -version 2>&1 | head -n 2
    docker_cmd --version
    mvn -version | head -n 1
    ant -version | head -n 1
    rg --version | head -n 1
}

main() {
    ensure_ubuntu_22

    if [[ "$(id -u)" -ne 0 ]]; then
        require_cmd sudo
    fi
    install_base_packages
    install_java_toolchains
    install_docker

    clone_or_update_repo "${UPFUZZ_REPO_URL}" "${UPFUZZ_BRANCH}" "${UPFUZZ_DIR}"
    clone_or_update_repo "${SSG_RUNTIME_REPO_URL}" "${SSG_RUNTIME_BRANCH}" "${SSG_RUNTIME_DIR}"

    build_ssg_runtime_and_copy_jar
    build_upfuzz

    copy_prebuild_archives_if_requested
    check_prebuild_archives
    pull_or_check_images

    write_env_hint
    print_versions

    log "Environment setup completed."
    log "If docker commands fail without sudo in a new shell, re-login to refresh docker group membership."
    log "Next: cd ${UPFUZZ_DIR} && scripts/runner/run_rolling_fuzzing.sh --help"
}

main "$@"
