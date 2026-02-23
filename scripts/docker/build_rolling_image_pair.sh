#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p "${LOG_DIR}"

JAVA8_HOME="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
JAVA11_HOME="${JAVA11_HOME:-/usr/lib/jvm/java-11-openjdk-amd64}"
JAVA17_HOME="${JAVA17_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

usage() {
    cat <<'EOF'
Usage:
  build_rolling_image_pair.sh <system> <originalVersion> <upgradedVersion>

Examples:
  build_rolling_image_pair.sh cassandra apache-cassandra-3.11.19 apache-cassandra-4.1.10
  build_rolling_image_pair.sh hdfs hadoop-2.10.2 hadoop-3.3.6
  build_rolling_image_pair.sh hbase hbase-2.5.13 hbase-2.6.4
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
    command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

run_with_log() {
    local logfile="$1"
    shift
    log "Running: $*"
    if ! "$@" >"${logfile}" 2>&1; then
        echo "----- tail(${logfile}) -----" >&2
        tail -n 120 "${logfile}" >&2 || true
        echo "----------------------------" >&2
        return 1
    fi
}

version_number() {
    local v="$1"
    case "$v" in
        apache-cassandra-*) echo "${v#apache-cassandra-}" ;;
        hadoop-*) echo "${v#hadoop-}" ;;
        hbase-*)
            v="${v#hbase-}"
            echo "${v%%-*}"
            ;;
        *) echo "$v" ;;
    esac
}

major_version() {
    local vnum
    vnum="$(version_number "$1")"
    echo "${vnum%%.*}"
}

ensure_archive_extracted() {
    local system="$1"
    local version="$2"
    local archive="${ROOT_DIR}/prebuild/${system}/${version}-src-instrumented.tar.gz"
    local dir="${ROOT_DIR}/prebuild/${system}/${version}"
    local srcdir="${ROOT_DIR}/prebuild/${system}/${version}-src"

    if [[ -d "${dir}" ]]; then
        log "Prebuild dir exists: ${dir}"
        return
    fi

    [[ -f "${archive}" ]] || die "Archive not found: ${archive}"
    log "Extracting ${archive}"
    tar -xzf "${archive}" -C "${ROOT_DIR}/prebuild/${system}"

    if [[ -d "${srcdir}" && ! -d "${dir}" ]]; then
        mv "${srcdir}" "${dir}"
    fi

    if [[ ! -d "${dir}" ]]; then
        local toproot
        toproot="$(tar -tzf "${archive}" | head -n1 | cut -d/ -f1)"
        if [[ -n "${toproot}" && -d "${ROOT_DIR}/prebuild/${system}/${toproot}" ]]; then
            mv "${ROOT_DIR}/prebuild/${system}/${toproot}" "${dir}"
        fi
    fi

    [[ -d "${dir}" ]] || die "Failed to materialize extracted dir for ${version} under prebuild/${system}"
}

overlay_archive_root_into_dir() {
    local archive="$1"
    local dest_dir="$2"
    local tmp_dir="${dest_dir}.dist_extract.$$"
    rm -rf "${tmp_dir}"
    mkdir -p "${tmp_dir}"
    tar -xzf "${archive}" -C "${tmp_dir}"

    local extracted_root
    extracted_root="$(find "${tmp_dir}" -mindepth 1 -maxdepth 1 -type d | head -n1 || true)"
    [[ -n "${extracted_root}" && -d "${extracted_root}" ]] || die "Cannot determine extracted root for ${archive}"

    cp -a "${extracted_root}/." "${dest_dir}/"
    rm -rf "${tmp_dir}"
}

pick_java_for_cassandra() {
    local version="$1"
    local major
    major="$(major_version "${version}")"
    if (( major >= 5 )); then
        echo "${JAVA17_HOME}"
    elif (( major >= 4 )); then
        echo "${JAVA11_HOME}"
    else
        echo "${JAVA8_HOME}"
    fi
}

patch_cassandra_daemon() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/cassandra/${version}"
    local major
    major="$(major_version "${version}")"

    if (( major >= 5 )); then
        cp -f "${ROOT_DIR}/src/main/resources/cqlsh_daemon5.py" "${dir}/bin/cqlsh_daemon.py"
    elif (( major >= 4 )); then
        cp -f "${ROOT_DIR}/src/main/resources/cqlsh_daemon4.py" "${dir}/bin/cqlsh_daemon.py"
    else
        cp -f "${ROOT_DIR}/src/main/resources/cqlsh_daemon2.py" "${dir}/bin/cqlsh_daemon.py"
    fi
}

materialize_cassandra_version() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/cassandra/${version}"
    local java_home
    java_home="$(pick_java_for_cassandra "${version}")"
    local major
    major="$(major_version "${version}")"
    local marker="${dir}/.upfuzz_materialized"
    local logfile="${LOG_DIR}/cassandra-${version}.log"

    [[ -d "${dir}" ]] || die "Missing Cassandra dir: ${dir}"

    if [[ ! -f "${marker}" ]]; then
        local ant_extra=""
        if (( major == 4 )); then
            ant_extra="-Duse.jdk11=true"
        fi
        run_with_log "${logfile}" bash -lc "cd '${dir}' && JAVA_HOME='${java_home}' PATH='${java_home}/bin:${PATH}' ANT_OPTS='-Xmx4g' ant ${ant_extra} -Drat.skip=true -Dskip.rat=true jar"
        touch "${marker}"
    else
        log "Skip Cassandra build (already materialized): ${version}"
    fi

    mkdir -p "${dir}/lib"
    if ! compgen -G "${dir}/lib/*.jar" >/dev/null; then
        if compgen -G "${dir}/build/lib/jars/*.jar" >/dev/null; then
            cp -f "${dir}"/build/lib/jars/*.jar "${dir}/lib/"
        fi
    fi

    if [[ -f "${ROOT_DIR}/lib/ssgFatJar.jar" ]]; then
        cp -f "${ROOT_DIR}/lib/ssgFatJar.jar" "${dir}/lib/ssgFatJar.jar"
    fi

    patch_cassandra_daemon "${version}"

    [[ -x "${dir}/bin/cassandra" ]] || die "Cassandra binary missing after materialization: ${dir}/bin/cassandra"
    [[ -f "${dir}/conf/cassandra.yaml" ]] || die "Cassandra conf missing: ${dir}/conf/cassandra.yaml"
}

patch_cassandra_upgrade_compat() {
    local original="$1"
    local upgraded="$2"
    local orig_major
    local up_major
    orig_major="$(major_version "${original}")"
    up_major="$(major_version "${upgraded}")"
    if (( orig_major == 3 && up_major == 4 )); then
        local yaml="${ROOT_DIR}/prebuild/cassandra/${upgraded}/conf/cassandra.yaml"
        if [[ -f "${yaml}" ]]; then
            sed -i 's/num_tokens: 16/num_tokens: 256/' "${yaml}" || true
        fi
    fi
}

build_cassandra_image_pair() {
    local original="$1"
    local upgraded="$2"
    local script="${ROOT_DIR}/src/main/resources/cassandra/upgrade-testing/compile-src/cassandra-clusternode.sh"
    local context="${ROOT_DIR}/src/main/resources/cassandra/upgrade-testing/compile-src"
    local tag="upfuzz_cassandra:${original}_${upgraded}"

    sed -i "s/^ORI_VERSION=apache-cassandra-.*/ORI_VERSION=${original}/" "${script}"
    sed -i "s/^UP_VERSION=apache-cassandra-.*/UP_VERSION=${upgraded}/" "${script}"
    log "Building Docker image: ${tag}"
    docker build "${context}" -t "${tag}"
}

build_hadoop_dist_if_needed() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/hdfs/${version}"
    local logfile="${LOG_DIR}/hdfs-${version}.log"
    local runtime_ready=0

    if [[ -x "${dir}/bin/hdfs" && -x "${dir}/sbin/hadoop-daemon.sh" && -d "${dir}/share/hadoop" ]]; then
        runtime_ready=1
    fi

    if (( runtime_ready == 1 )); then
        log "Skip HDFS dist build (runtime already present): ${version}"
        return
    fi

    local base_opts="-DskipTests -DskipITs -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Drat.skip=true -Dtar -Pdist package"
    local pl_modules="hadoop-dist,hadoop-common-project/hadoop-nfs,hadoop-hdfs-project/hadoop-hdfs-httpfs"
    local cmd1="cd '${dir}' && JAVA_HOME='${JAVA8_HOME}' PATH='${JAVA8_HOME}/bin:${PATH}' MAVEN_OPTS='-Xmx6g' mvn ${base_opts} -pl ${pl_modules} -am"
    local cmd2="cd '${dir}' && JAVA_HOME='${JAVA11_HOME}' PATH='${JAVA11_HOME}/bin:${PATH}' MAVEN_OPTS='-Xmx6g' mvn ${base_opts} -pl ${pl_modules} -am"
    local cmd3="cd '${dir}' && JAVA_HOME='${JAVA11_HOME}' PATH='${JAVA11_HOME}/bin:${PATH}' MAVEN_OPTS='-Xmx6g' mvn ${base_opts}"

    if ! run_with_log "${logfile}" bash -lc "${cmd1}"; then
        if ! run_with_log "${logfile}" bash -lc "${cmd2}"; then
            run_with_log "${logfile}" bash -lc "${cmd3}"
        fi
    fi

    local dist_tar
    dist_tar="$(find "${dir}" -type f \( -path "*/hadoop-dist/target/${version}.tar.gz" -o -path "*/hadoop-project-dist/target/${version}.tar.gz" \) | sort | tail -n1 || true)"
    if [[ -z "${dist_tar}" ]]; then
        dist_tar="$(find "${dir}" -type f \( -path "*/hadoop-dist/target/hadoop-*.tar.gz" -o -path "*/hadoop-project-dist/target/hadoop-*.tar.gz" \) | grep -v -- '-src.tar.gz' | grep -v -- 'hadoop-project-dist-' | sort | tail -n1 || true)"
    fi
    [[ -n "${dist_tar}" ]] || die "Cannot find built Hadoop dist tar.gz for ${version}"

    overlay_archive_root_into_dir "${dist_tar}" "${dir}"

    [[ -x "${dir}/bin/hdfs" ]] || die "HDFS bin/hdfs missing after dist extraction for ${version}"
    [[ -x "${dir}/sbin/hadoop-daemon.sh" ]] || die "HDFS sbin/hadoop-daemon.sh missing for ${version}"
}

patch_hdfs_daemon() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/hdfs/${version}"
    local major
    major="$(major_version "${version}")"

    if (( major == 2 )); then
        cp -f "${ROOT_DIR}/src/main/resources/FsShellDaemon2.java" "${dir}/FsShellDaemon.java"
    else
        cp -f "${ROOT_DIR}/src/main/resources/FsShellDaemon_trunk.java" "${dir}/FsShellDaemon.java"
    fi

    "${JAVA8_HOME}/bin/javac" -d "${dir}" -cp "${dir}/share/hadoop/hdfs/*:${dir}/share/hadoop/common/*:${dir}/share/hadoop/common/lib/*" "${dir}/FsShellDaemon.java"

    if ! grep -q "dfsdaemon" "${dir}/bin/hdfs"; then
        if (( major == 2 )); then
            sed -i 's/elif \[ "\$COMMAND" = "dfs" \] ; then/elif [ "\$COMMAND" = "dfsdaemon" ] ; then\n  CLASS=org.apache.hadoop.fs.FsShellDaemon\n  HADOOP_OPTS="\$HADOOP_OPTS \$HADOOP_CLIENT_OPTS"\n&/' "${dir}/bin/hdfs"
        else
            sed -i 's/  case ${subcmd} in/&\n    dfsdaemon)\n      HADOOP_CLASSNAME="org.apache.hadoop.fs.FsShellDaemon"\n    ;;/' "${dir}/bin/hdfs"
        fi
    fi
}

materialize_hdfs_version() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/hdfs/${version}"
    [[ -d "${dir}" ]] || die "Missing HDFS dir: ${dir}"

    build_hadoop_dist_if_needed "${version}"
    patch_hdfs_daemon "${version}"

    if [[ -f "${ROOT_DIR}/lib/ssgFatJar.jar" ]]; then
        cp -f "${ROOT_DIR}/lib/ssgFatJar.jar" "${dir}/share/hadoop/common/lib/ssgFatJar.jar"
        cp -f "${ROOT_DIR}/lib/ssgFatJar.jar" "${dir}/share/hadoop/hdfs/lib/ssgFatJar.jar"
    fi

    [[ -x "${dir}/bin/hdfs" ]] || die "HDFS bin/hdfs missing: ${dir}/bin/hdfs"
    [[ -x "${dir}/sbin/hadoop-daemon.sh" ]] || die "HDFS daemon missing: ${dir}/sbin/hadoop-daemon.sh"
}

build_hdfs_image_pair() {
    local original="$1"
    local upgraded="$2"
    local script="${ROOT_DIR}/src/main/resources/hdfs/compile-src/hdfs-clusternode.sh"
    local context="${ROOT_DIR}/src/main/resources/hdfs/compile-src"
    local tag="upfuzz_hdfs:${original}_${upgraded}"

    sed -i "s/^ORI_VERSION=hadoop-.*/ORI_VERSION=${original}/" "${script}"
    sed -i "s/^UPG_VERSION=hadoop-.*/UPG_VERSION=${upgraded}/" "${script}"
    log "Building Docker image: ${tag}"
    docker build "${context}" -t "${tag}"
}

pick_java_for_hbase() {
    local version="$1"
    local major
    major="$(major_version "${version}")"
    if (( major >= 3 )); then
        echo "${JAVA17_HOME}"
    else
        echo "${JAVA8_HOME}"
    fi
}

build_hbase_dist_if_needed() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/hbase/${version}"
    local logfile="${LOG_DIR}/hbase-${version}.log"
    local java_home
    java_home="$(pick_java_for_hbase "${version}")"

    if [[ -x "${dir}/bin/hbase" && -x "${dir}/bin/hbase-daemon.sh" ]] && compgen -G "${dir}/lib/*.jar" >/dev/null; then
        log "Skip HBase dist build (runtime already present): ${version}"
        return
    fi

    local extra_profile=""
    if (( "$(major_version "${version}")" >= 3 )); then
        extra_profile="-Phadoop-3.0"
    fi

    local cmd
    cmd="cd '${dir}' && JAVA_HOME='${java_home}' PATH='${java_home}/bin:${PATH}' MAVEN_OPTS='-Xmx6g' mvn ${extra_profile} -DskipTests -DskipITs -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Drat.skip=true -Denforcer.skip=true -Dskip.license.check=true -Dwarbucks.skip=true -pl hbase-assembly -am package assembly:single"
    run_with_log "${logfile}" bash -lc "${cmd}"

    local dist_tar
    dist_tar="$(find "${dir}" -type f -path "*/hbase-assembly/target/hbase-*-bin.tar.gz" | grep -v client | sort | tail -n1 || true)"
    [[ -n "${dist_tar}" ]] || die "Cannot find built HBase bin tar.gz for ${version}"

    overlay_archive_root_into_dir "${dist_tar}" "${dir}"

    [[ -x "${dir}/bin/hbase" ]] || die "HBase bin/hbase missing after dist extraction for ${version}"
    [[ -x "${dir}/bin/hbase-daemon.sh" ]] || die "HBase daemon missing for ${version}"
}

patch_hbase_runtime_files() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/hbase/${version}"
    local major
    major="$(major_version "${version}")"

    if (( major >= 3 )); then
        cp -f "${ROOT_DIR}/src/main/resources/hbase/compile-src/hbase-env-jdk17.sh" "${dir}/conf/hbase-env.sh"
    else
        cp -f "${ROOT_DIR}/src/main/resources/hbase/compile-src/hbase-env.sh" "${dir}/conf/hbase-env.sh"
    fi
    cp -f "${ROOT_DIR}/src/main/resources/hbase/compile-src/hbase_daemon3.py" "${dir}/bin/hbase_daemon.py"
    if [[ -f "${ROOT_DIR}/lib/ssgFatJar.jar" ]]; then
        cp -f "${ROOT_DIR}/lib/ssgFatJar.jar" "${dir}/lib/ssgFatJar.jar"
    fi
}

ensure_hbase_dep_hadoop_2102() {
    local dep_dir="${ROOT_DIR}/prebuild/hadoop/hadoop-2.10.2"
    local dep_img="upfuzz_hdfs:hadoop-2.10.2"
    mkdir -p "${ROOT_DIR}/prebuild/hadoop"

    if [[ ! -d "${dep_dir}" || ! -x "${dep_dir}/bin/hdfs" ]]; then
        ensure_archive_extracted "hdfs" "hadoop-2.10.2"
        materialize_hdfs_version "hadoop-2.10.2"
        rm -rf "${dep_dir}"
        cp -a "${ROOT_DIR}/prebuild/hdfs/hadoop-2.10.2" "${dep_dir}"
    fi

    cp -f "${ROOT_DIR}/src/main/resources/hdfs/hbase-pure/core-site.xml" "${dep_dir}/etc/hadoop/core-site.xml"
    cp -f "${ROOT_DIR}/src/main/resources/hdfs/hbase-pure/hdfs-site.xml" "${dep_dir}/etc/hadoop/hdfs-site.xml"
    cp -f "${ROOT_DIR}/src/main/resources/hdfs/hbase-pure/hadoop-env.sh" "${dep_dir}/etc/hadoop/hadoop-env.sh"

    if ! docker image inspect "${dep_img}" >/dev/null 2>&1; then
        log "Building dependency Docker image: ${dep_img}"
        docker build "${ROOT_DIR}/src/main/resources/hdfs/hbase-pure" -t "${dep_img}"
    else
        log "Dependency Docker image already exists: ${dep_img}"
    fi
}

materialize_hbase_version() {
    local version="$1"
    local dir="${ROOT_DIR}/prebuild/hbase/${version}"
    [[ -d "${dir}" ]] || die "Missing HBase dir: ${dir}"

    build_hbase_dist_if_needed "${version}"
    patch_hbase_runtime_files "${version}"

    [[ -x "${dir}/bin/hbase" ]] || die "HBase bin/hbase missing: ${dir}/bin/hbase"
    [[ -x "${dir}/bin/hbase-daemon.sh" ]] || die "HBase daemon missing: ${dir}/bin/hbase-daemon.sh"
}

build_hbase_image_pair() {
    local original="$1"
    local upgraded="$2"
    local context="${ROOT_DIR}/src/main/resources/hbase/compile-src"
    local tag="upfuzz_hbase:${original}_${upgraded}"
    log "Building Docker image: ${tag}"
    docker build "${context}" -t "${tag}"
}

verify_args() {
    local system="$1"
    local original="$2"
    local upgraded="$3"
    case "${system}" in
        cassandra)
            [[ "${original}" == apache-cassandra-* ]] || die "Cassandra version must start with apache-cassandra-"
            [[ "${upgraded}" == apache-cassandra-* ]] || die "Cassandra version must start with apache-cassandra-"
            ;;
        hdfs)
            [[ "${original}" == hadoop-* ]] || die "HDFS version must start with hadoop-"
            [[ "${upgraded}" == hadoop-* ]] || die "HDFS version must start with hadoop-"
            ;;
        hbase)
            [[ "${original}" == hbase-* ]] || die "HBase version must start with hbase-"
            [[ "${upgraded}" == hbase-* ]] || die "HBase version must start with hbase-"
            ;;
        *)
            die "Unsupported system: ${system}"
            ;;
    esac
}

main() {
    [[ $# -eq 3 ]] || { usage; exit 1; }
    local system="$1"
    local original="$2"
    local upgraded="$3"

    require_cmd docker
    require_cmd tar
    require_cmd sed
    require_cmd ant
    require_cmd mvn
    require_cmd javac

    verify_args "${system}" "${original}" "${upgraded}"

    case "${system}" in
        cassandra)
            ensure_archive_extracted "cassandra" "${original}"
            ensure_archive_extracted "cassandra" "${upgraded}"
            materialize_cassandra_version "${original}"
            materialize_cassandra_version "${upgraded}"
            patch_cassandra_upgrade_compat "${original}" "${upgraded}"
            build_cassandra_image_pair "${original}" "${upgraded}"
            ;;
        hdfs)
            ensure_archive_extracted "hdfs" "${original}"
            ensure_archive_extracted "hdfs" "${upgraded}"
            materialize_hdfs_version "${original}"
            materialize_hdfs_version "${upgraded}"
            build_hdfs_image_pair "${original}" "${upgraded}"
            ;;
        hbase)
            ensure_archive_extracted "hbase" "${original}"
            ensure_archive_extracted "hbase" "${upgraded}"
            ensure_hbase_dep_hadoop_2102
            materialize_hbase_version "${original}"
            materialize_hbase_version "${upgraded}"
            build_hbase_image_pair "${original}" "${upgraded}"
            ;;
    esac

    log "Completed phase 1-3 for ${system}: ${original} -> ${upgraded}"
}

main "$@"
