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
MODE="cassandra-demo"
SKIP_DEMO_PREBUILD_MATERIALIZE=false
USE_EXISTING_REPOS=false

usage() {
    cat <<EOF
Usage:
  $(basename "$0") [options]

Options:
  --mode <cassandra-demo|full> Select setup mode (default: ${MODE})
  --workspace-root <dir>       Workspace root (default: ${WORKSPACE_ROOT})
  --upfuzz-dir <dir>           UpFuzz directory (default: <workspace-root>/upfuzz)
  --ssg-runtime-dir <dir>      ssg-runtime directory (default: <workspace-root>/ssg-runtime)
  --prebuild-source-dir <dir>  Optional source dir to copy required prebuild archives from
  --pull-images                Pull expected images from registry prefix and tag locally
  --image-prefix <prefix>      Registry/org prefix for pull, e.g. shuaiwang516
  --skip-build                 Skip ./gradlew build steps for ssg-runtime and upfuzz
  --use-existing-repos         Use existing local repo directories as-is (no git clone/fetch/pull)
  --skip-demo-prebuild-materialize  In cassandra-demo mode, do not extract/build Cassandra prebuild from instrumented archives
  --skip-prebuild-check        Skip required prebuild archive checks
  --skip-image-check           Skip docker image checks
  -h, --help                   Show this help

Examples:
  $(basename "$0")
  $(basename "$0") --workspace-root /mydata/rupfuzz
  $(basename "$0") --mode full --prebuild-source-dir /path/to/prebuild_bundle
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
        --mode)
            MODE="$2"
            shift 2
            ;;
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
        --use-existing-repos)
            USE_EXISTING_REPOS=true
            shift
            ;;
        --skip-demo-prebuild-materialize|--skip-demo-prebuild-download)
            SKIP_DEMO_PREBUILD_MATERIALIZE=true
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
if [[ "${MODE}" != "cassandra-demo" && "${MODE}" != "full" ]]; then
    die "--mode must be one of: cassandra-demo, full"
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

DEMO_CASSANDRA_VERSIONS=(
    "apache-cassandra-4.1.10"
    "apache-cassandra-5.0.6"
)

DEMO_CASSANDRA_ARCHIVE_REQUIRED=(
    "prebuild/cassandra/apache-cassandra-4.1.10-src-instrumented.tar.gz"
    "prebuild/cassandra/apache-cassandra-5.0.6-src-instrumented.tar.gz"
)

DEMO_IMAGE_REQUIRED=(
    "upfuzz_cassandra:apache-cassandra-4.1.10_apache-cassandra-5.0.6"
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

    if ! docker compose version >/dev/null 2>&1; then
        log "docker compose is missing; installing compose plugin"
        run_as_root apt-get update
        if apt-cache show docker-compose-v2 >/dev/null 2>&1; then
            run_as_root apt-get install -y docker-compose-v2
        elif apt-cache show docker-compose-plugin >/dev/null 2>&1; then
            run_as_root apt-get install -y docker-compose-plugin
        else
            die "No docker compose package found (docker-compose-v2/docker-compose-plugin)."
        fi
    fi
    docker compose version >/dev/null 2>&1 || die "docker compose still unavailable after install"

    if getent group docker >/dev/null 2>&1; then
        run_as_root usermod -aG docker "${TARGET_USER}"
    fi
}

clone_or_update_repo() {
    local url="$1"
    local branch="$2"
    local dir="$3"

    if [[ "${USE_EXISTING_REPOS}" == true ]]; then
        [[ -d "${dir}" ]] || die "--use-existing-repos set but directory not found: ${dir}"
        log "Using existing repo without git sync: ${dir}"
        return
    fi

    mkdir -p "$(dirname "${dir}")"
    if [[ -d "${dir}/.git" ]]; then
        log "Updating repo: ${dir}"
        git -C "${dir}" fetch --all --tags
    elif [[ -d "${dir}" ]]; then
        die "Directory exists but is not a git repo: ${dir}. Use --use-existing-repos to skip git clone/pull."
    else
        log "Cloning repo: ${url} -> ${dir}"
        git clone "${url}" "${dir}"
    fi

    git -C "${dir}" checkout "${branch}"
    git -C "${dir}" pull --ff-only origin "${branch}"
}

java11_home() {
    if [[ -d /usr/lib/jvm/java-11-openjdk-amd64 ]]; then
        echo "/usr/lib/jvm/java-11-openjdk-amd64"
        return
    fi
    if command -v javac >/dev/null 2>&1; then
        local detected
        detected="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
        if [[ -n "${detected}" && -d "${detected}" ]]; then
            echo "${detected}"
            return
        fi
    fi
    die "Cannot determine Java 11 home for Gradle launcher"
}

build_ssg_runtime_and_copy_jar() {
    if [[ "${SKIP_BUILD}" == true ]]; then
        log "Skipping ssg-runtime build (--skip-build)"
        return
    fi

    log "Building ssg-runtime fat jar"
    chmod +x "${SSG_RUNTIME_DIR}/gradlew"
    local j11
    j11="$(java11_home)"
    (
        cd "${SSG_RUNTIME_DIR}"
        JAVA_HOME="${j11}" PATH="${j11}/bin:${PATH}" ./gradlew --no-daemon fatJar
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
    local j11
    j11="$(java11_home)"
    (
        cd "${UPFUZZ_DIR}"
        JAVA_HOME="${j11}" PATH="${j11}/bin:${PATH}" ./gradlew --no-daemon copyDependencies
        if [[ "${USE_EXISTING_REPOS}" == true && ! -d "${UPFUZZ_DIR}/.git" ]]; then
            log "No .git metadata in upfuzz dir; using assemble target to avoid spotless git checks"
            JAVA_HOME="${j11}" PATH="${j11}/bin:${PATH}" ./gradlew --no-daemon assemble -x test
        else
            JAVA_HOME="${j11}" PATH="${j11}/bin:${PATH}" ./gradlew --no-daemon build -x test
        fi
    )
}

copy_prebuild_archives_if_requested() {
    if [[ "${MODE}" != "full" ]]; then
        return
    fi
    [[ -n "${PREBUILD_SOURCE_DIR}" ]] || return 0
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

copy_demo_cassandra_archives_if_requested() {
    if [[ "${MODE}" != "cassandra-demo" ]]; then
        return
    fi
    [[ -n "${PREBUILD_SOURCE_DIR}" ]] || return 0
    log "Attempting to copy Cassandra demo instrumented archives from ${PREBUILD_SOURCE_DIR}"

    local rel src1 src2 dst
    for rel in "${DEMO_CASSANDRA_ARCHIVE_REQUIRED[@]}"; do
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

pick_java_for_cassandra_demo() {
    local version="$1"
    if [[ "${version}" == apache-cassandra-5.* ]]; then
        echo "/usr/lib/jvm/java-17-openjdk-amd64"
    elif [[ "${version}" == apache-cassandra-4.* ]]; then
        echo "/usr/lib/jvm/java-11-openjdk-amd64"
    else
        echo "/usr/lib/jvm/java-8-openjdk-amd64"
    fi
}

extract_instrumented_archive_if_needed() {
    local archive="$1"
    local destination="$2"
    local parent
    parent="$(dirname "${destination}")"

    if [[ -d "${destination}" ]]; then
        if [[ -f "${destination}/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java" ]]; then
            return
        fi
        log "Existing ${destination} is not instrumented. Replacing from $(basename "${archive}")"
        rm -rf "${destination}"
    fi

    tar -xzf "${archive}" -C "${parent}"
    if [[ -d "${parent}/$(basename "${destination}")-src" && ! -d "${destination}" ]]; then
        mv "${parent}/$(basename "${destination}")-src" "${destination}"
    fi
    if [[ ! -d "${destination}" ]]; then
        local toproot
        toproot="$(tar -tzf "${archive}" | head -n1 | cut -d/ -f1)"
        if [[ -n "${toproot}" && -d "${parent}/${toproot}" ]]; then
            mv "${parent}/${toproot}" "${destination}"
        fi
    fi
    [[ -d "${destination}" ]] || die "Failed to extract ${archive} into ${destination}"
}

materialize_demo_cassandra_prebuild() {
    if [[ "${MODE}" != "cassandra-demo" ]]; then
        return
    fi
    if [[ "${SKIP_DEMO_PREBUILD_MATERIALIZE}" == true ]]; then
        log "Skipping demo Cassandra prebuild materialization (--skip-demo-prebuild-materialize)"
        return
    fi

    local prebuild_dir="${UPFUZZ_DIR}/prebuild/cassandra"
    mkdir -p "${prebuild_dir}"

    local version archive_path extracted_dir marker java_home ant_extra major daemon_src
    for version in "${DEMO_CASSANDRA_VERSIONS[@]}"; do
        archive_path="${prebuild_dir}/${version}-src-instrumented.tar.gz"
        extracted_dir="${prebuild_dir}/${version}"
        marker="${extracted_dir}/.upfuzz_materialized"

        [[ -f "${archive_path}" ]] || die "Missing demo instrumented archive: ${archive_path}. Provide it under prebuild/cassandra or --prebuild-source-dir."
        extract_instrumented_archive_if_needed "${archive_path}" "${extracted_dir}"

        major="$(echo "${version}" | sed -E 's/^apache-cassandra-([0-9]+).*/\1/')"
        java_home="$(pick_java_for_cassandra_demo "${version}")"
        ant_extra=""
        if (( major == 4 )); then
            ant_extra="-Duse.jdk11=true"
        fi

        if [[ ! -f "${marker}" ]]; then
            log "Materializing ${version} with ant"
            (
                cd "${extracted_dir}"
                JAVA_HOME="${java_home}" PATH="${java_home}/bin:${PATH}" ANT_OPTS='-Xmx4g' ant ${ant_extra} -Drat.skip=true -Dskip.rat=true jar
            )
            touch "${marker}"
        fi

        mkdir -p "${extracted_dir}/lib"
        if ! compgen -G "${extracted_dir}/lib/*.jar" >/dev/null; then
            if compgen -G "${extracted_dir}/build/lib/jars/*.jar" >/dev/null; then
                cp -f "${extracted_dir}"/build/lib/jars/*.jar "${extracted_dir}/lib/"
            fi
        fi

        if [[ -f "${UPFUZZ_DIR}/lib/ssgFatJar.jar" ]]; then
            cp -f "${UPFUZZ_DIR}/lib/ssgFatJar.jar" "${extracted_dir}/lib/ssgFatJar.jar"
        fi

        if (( major >= 5 )); then
            daemon_src="${UPFUZZ_DIR}/src/main/resources/cqlsh_daemon5.py"
        elif (( major >= 4 )); then
            daemon_src="${UPFUZZ_DIR}/src/main/resources/cqlsh_daemon4.py"
        else
            daemon_src="${UPFUZZ_DIR}/src/main/resources/cqlsh_daemon2.py"
        fi
        cp -f "${daemon_src}" "${extracted_dir}/bin/cqlsh_daemon.py"

        [[ -x "${extracted_dir}/bin/cassandra" ]] || die "Cassandra binary missing after materialization: ${extracted_dir}/bin/cassandra"
        [[ -f "${extracted_dir}/conf/cassandra.yaml" ]] || die "Cassandra config missing: ${extracted_dir}/conf/cassandra.yaml"
        [[ -f "${extracted_dir}/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java" ]] || die "Instrumented hook not found: ${extracted_dir}/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java"
    done
}

check_prebuild_archives() {
    if [[ "${SKIP_PREBUILD_CHECK}" == true ]]; then
        log "Skipping prebuild archive check (--skip-prebuild-check)"
        return
    fi

    if [[ "${MODE}" == "cassandra-demo" ]]; then
        log "Checking Cassandra demo prebuild assets"
        local missing_dirs=()
        local missing_hooks=()
        local version version_dir
        for version in "${DEMO_CASSANDRA_VERSIONS[@]}"; do
            version_dir="${UPFUZZ_DIR}/prebuild/cassandra/${version}"
            if [[ ! -x "${version_dir}/bin/cassandra" || ! -f "${version_dir}/conf/cassandra.yaml" ]]; then
                missing_dirs+=("${version_dir}")
                continue
            fi
            if [[ ! -f "${version_dir}/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java" ]]; then
                missing_hooks+=("${version_dir}")
            fi
        done
        if (( ${#missing_dirs[@]} > 0 )); then
            local missing_archives=()
            local rel
            for rel in "${DEMO_CASSANDRA_ARCHIVE_REQUIRED[@]}"; do
                if [[ ! -f "${UPFUZZ_DIR}/${rel}" ]]; then
                    missing_archives+=("${rel}")
                fi
            done
            if (( ${#missing_archives[@]} > 0 )); then
                echo "Missing required Cassandra demo archives under ${UPFUZZ_DIR}:" >&2
                printf '  - %s\n' "${missing_archives[@]}" >&2
                echo "Provide instrumented archives and re-run (or use --prebuild-source-dir)." >&2
                exit 2
            fi
            echo "Missing required Cassandra demo prebuild directories:" >&2
            printf '  - %s\n' "${missing_dirs[@]}" >&2
            echo "Re-run without --skip-demo-prebuild-materialize to materialize from instrumented archives." >&2
            exit 2
        fi
        if (( ${#missing_hooks[@]} > 0 )); then
            echo "Cassandra demo directories exist but instrumentation hook is missing:" >&2
            printf '  - %s\n' "${missing_hooks[@]}" >&2
            echo "Replace them with instrumented prebuild archives and rerun setup." >&2
            exit 2
        fi
    else
        log "Checking required prebuild archives (full mode)"
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

    local image_targets=()
    if [[ "${MODE}" == "cassandra-demo" ]]; then
        image_targets=("${DEMO_IMAGE_REQUIRED[@]}")
    else
        image_targets=("${IMAGE_REQUIRED[@]}")
    fi

    log "Checking required docker images (${MODE})"
    local image remote missing=()
    for image in "${image_targets[@]}"; do
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
    copy_demo_cassandra_archives_if_requested
    materialize_demo_cassandra_prebuild
    check_prebuild_archives
    pull_or_check_images

    write_env_hint
    print_versions

    log "Environment setup completed."
    log "If docker commands fail without sudo in a new shell, re-login to refresh docker group membership."
    log "Next: cd ${UPFUZZ_DIR} && scripts/runner/run_rolling_fuzzing.sh --help"
}

main "$@"
