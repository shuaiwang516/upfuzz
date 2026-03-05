#!/usr/bin/env bash
set -euo pipefail

PREBUILD_ROOT="/home/shuai/xlab/rupfuzz/prebuild"
UPFUZZ_ROOT="/home/shuai/xlab/rupfuzz/upfuzz-shuai"
LOG_DIR="${PREBUILD_ROOT}/build-binary-logs"
SSG_JAR="${PREBUILD_ROOT}/ssgFatJar.jar"

JAVA8="/usr/lib/jvm/java-1.8.0-openjdk-amd64"
JAVA11="/usr/lib/jvm/java-1.11.0-openjdk-amd64"
JAVA17="/usr/lib/jvm/java-1.17.0-openjdk-amd64"

mkdir -p "${LOG_DIR}"

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

run_logged() {
  local name="$1"
  shift
  local logfile="${LOG_DIR}/${name}.log"
  log "RUN ${name}"
  if "$@" >"${logfile}" 2>&1; then
    log "OK  ${name}"
  else
    log "FAIL ${name} (log: ${logfile})"
    tail -n 120 "${logfile}" >&2 || true
    exit 1
  fi
}

assert_jar_contains_entry() {
  local jar_file="$1"
  local entry_text="$2"
  local label="$3"
  local entries
  [[ -f "${jar_file}" ]] || die "missing jar for ${label}: ${jar_file}"
  entries="$(jar tf "${jar_file}" || true)"
  [[ "${entries}" == *"${entry_text}"* ]] || die "missing ${label} in ${jar_file}"
}

assert_jar_class_contains() {
  local jar_file="$1"
  local class_entry="$2"
  local content_text="$3"
  local label="$4"
  local class_text
  [[ -f "${jar_file}" ]] || die "missing jar for ${label}: ${jar_file}"
  class_text="$(unzip -p "${jar_file}" "${class_entry}" 2>/dev/null | strings || true)"
  [[ "${class_text}" == *"${content_text}"* ]] \
    || die "missing ${label} (${content_text}) in ${jar_file}:${class_entry}"
}

verify_cassandra_network_trace() {
  local version="$1"
  local dir="$2"
  local short="${version#apache-cassandra-}"
  local main_jar="${dir}/build/apache-cassandra-${short}-SNAPSHOT.jar"

  assert_jar_contains_entry "${main_jar}" 'org/apache/cassandra/net/NetTraceRuntimeBridge.class' \
    "cassandra bridge class"
  assert_jar_class_contains "${main_jar}" "org/apache/cassandra/net/NetTraceRuntimeBridge.class" \
    'org.zlab.net.tracker.Runtime' "cassandra bridge runtime reflection target"

  if [[ "${version}" == apache-cassandra-3.* ]]; then
    assert_jar_class_contains "${main_jar}" "org/apache/cassandra/net/MessagingService.class" \
      'MessagingService.sendOneWay' "cassandra send hook marker"
    assert_jar_class_contains "${main_jar}" "org/apache/cassandra/net/IncomingTcpConnection.class" \
      'IncomingTcpConnection.receiveMessage' "cassandra receive hook marker"
  else
    assert_jar_class_contains "${main_jar}" "org/apache/cassandra/net/MessagingService.class" \
      'MessagingService.doSend' "cassandra send hook marker"
    assert_jar_class_contains "${main_jar}" 'org/apache/cassandra/net/InboundMessageHandler$ProcessMessage.class' \
      'InboundMessageHandler.ProcessMessage.run' "cassandra receive hook marker"
  fi
}

verify_hdfs_network_trace() {
  local version="$1"
  local runtime_dir="$2"
  local short="${version#hadoop-}"
  local common_jar

  common_jar="$(find "${runtime_dir}/share/hadoop/common" -maxdepth 1 -type f -name "hadoop-common-${short}*.jar" ! -name "*-tests.jar" ! -name "*-sources.jar" | head -n1)"
  [[ -n "${common_jar}" ]] || die "cannot find hadoop-common jar for ${version}"

  assert_jar_contains_entry "${common_jar}" 'org/apache/hadoop/ipc/NetTraceRuntimeBridge.class' \
    "hdfs bridge class"
  assert_jar_class_contains "${common_jar}" "org/apache/hadoop/ipc/NetTraceRuntimeBridge.class" \
    'org.zlab.net.tracker.Runtime' "hdfs bridge runtime reflection target"
  assert_jar_class_contains "${common_jar}" "org/apache/hadoop/ipc/Client.class" \
    'Client.call' "hdfs send hook marker"
  assert_jar_class_contains "${common_jar}" 'org/apache/hadoop/ipc/Server$Connection.class' \
    'Server.Connection.processRpcRequest' "hdfs receive hook marker"

  [[ -f "${runtime_dir}/org/apache/hadoop/fs/FsShellDaemon.class" ]] || die "missing FsShellDaemon.class in ${runtime_dir}"
  strings "${runtime_dir}/org/apache/hadoop/fs/FsShellDaemon.class" | rg -q 'org/zlab/net/tracker/Runtime' \
    || die "FsShellDaemon.class missing direct net-trace runtime calls in ${runtime_dir}"
}

verify_hbase_network_trace() {
  local version="$1"
  local runtime_dir="$2"
  local short="${version#hbase-}"
  local client_jar server_jar

  client_jar="$(find "${runtime_dir}/lib" -maxdepth 1 -type f -name "hbase-client-${short}*.jar" ! -name "*-tests.jar" ! -name "*-sources.jar" | head -n1)"
  server_jar="$(find "${runtime_dir}/lib" -maxdepth 1 -type f -name "hbase-server-${short}*.jar" ! -name "*-tests.jar" ! -name "*-sources.jar" | head -n1)"

  [[ -n "${client_jar}" ]] || die "cannot find hbase-client jar for ${version}"
  [[ -n "${server_jar}" ]] || die "cannot find hbase-server jar for ${version}"

  assert_jar_contains_entry "${client_jar}" 'org/apache/hadoop/hbase/ipc/NetTraceRuntimeBridge.class' \
    "hbase bridge class"
  assert_jar_class_contains "${client_jar}" "org/apache/hadoop/hbase/ipc/NetTraceRuntimeBridge.class" \
    'org.zlab.net.tracker.Runtime' "hbase bridge runtime reflection target"
  assert_jar_class_contains "${client_jar}" "org/apache/hadoop/hbase/ipc/AbstractRpcClient.class" \
    'AbstractRpcClient.callMethod' "hbase send hook marker"
  assert_jar_class_contains "${server_jar}" "org/apache/hadoop/hbase/ipc/ServerRpcConnection.class" \
    'ServerRpcConnection.processRequest' "hbase receive hook marker"
}

extract_src_archive() {
  local system="$1"
  local version="$2"
  local base="${PREBUILD_ROOT}/${system}"
  local archive="${base}/${version}-src-instrumented.tar.gz"
  local target="${base}/${version}"
  local src_alias="${base}/${version}-src"

  [[ -f "${archive}" ]] || die "missing source archive: ${archive}"
  rm -rf "${target}" "${src_alias}"
  mkdir -p "${base}"

  run_logged "extract_${system}_${version}" tar -xzf "${archive}" -C "${base}"

  if [[ -d "${src_alias}" && ! -d "${target}" ]]; then
    mv "${src_alias}" "${target}"
  fi
  if [[ ! -d "${target}" ]]; then
    local root
    root="$(tar -tzf "${archive}" | sed -n '1p' | cut -d/ -f1)"
    [[ -n "${root}" && -d "${base}/${root}" ]] || die "cannot locate extracted root for ${archive}"
    if [[ "${root}" != "${version}" ]]; then
      mv "${base}/${root}" "${target}"
    fi
  fi
  [[ -d "${target}" ]] || die "extract failed for ${system}/${version}"
}

pack_binary_tar() {
  local base="$1"
  local version="$2"
  local target_tar="${base}/${version}.tar.gz"
  rm -f "${target_tar}"
  run_logged "pack_${version}" tar -czf "${target_tar}" -C "${base}" "${version}"
}

pick_cqlsh_daemon() {
  local version="$1"
  if [[ "${version}" == apache-cassandra-5* ]]; then
    echo "cqlsh_daemon5.py"
  elif [[ "${version}" == apache-cassandra-4* ]]; then
    echo "cqlsh_daemon4.py"
  else
    echo "cqlsh_daemon2.py"
  fi
}

build_cassandra_version() {
  local version="$1"
  local base="${PREBUILD_ROOT}/cassandra"
  local dir="${base}/${version}"
  local java_home ant_extra daemon

  extract_src_archive "cassandra" "${version}"

  case "${version}" in
    apache-cassandra-3.*)
      java_home="${JAVA8}"
      ant_extra=()
      ;;
    apache-cassandra-4.*)
      java_home="${JAVA11}"
      ant_extra=("-Duse.jdk11=true")
      ;;
    apache-cassandra-5.*)
      java_home="${JAVA17}"
      ant_extra=()
      ;;
    *)
      die "unsupported cassandra version: ${version}"
      ;;
  esac

  [[ -x "${java_home}/bin/java" ]] || die "java missing: ${java_home}"
  [[ -f "${SSG_JAR}" ]] || die "missing ssg jar: ${SSG_JAR}"

  mkdir -p "${dir}/lib" "${dir}/build/lib/jars"
  cp -f "${SSG_JAR}" "${dir}/lib/ssgFatJar.jar"
  cp -f "${SSG_JAR}" "${dir}/build/lib/jars/ssgFatJar.jar"

  run_logged "build_cassandra_${version}" bash -lc "cd '${dir}' && JAVA_HOME='${java_home}' PATH='${java_home}/bin:${PATH}' ANT_OPTS='-Xmx4g' ant ${ant_extra[*]:-} -Drat.skip=true -Dskip.rat=true jar"
  verify_cassandra_network_trace "${version}" "${dir}"

  if ! compgen -G "${dir}/lib/*.jar" >/dev/null; then
    if compgen -G "${dir}/build/lib/jars/*.jar" >/dev/null; then
      cp -f "${dir}"/build/lib/jars/*.jar "${dir}/lib/"
    fi
  fi

  daemon="$(pick_cqlsh_daemon "${version}")"
  cp -f "${UPFUZZ_ROOT}/src/main/resources/${daemon}" "${dir}/bin/cqlsh_daemon.py"

  [[ -x "${dir}/bin/cassandra" ]] || die "missing cassandra binary: ${dir}/bin/cassandra"
  [[ -f "${dir}/conf/cassandra.yaml" ]] || die "missing cassandra config: ${dir}/conf/cassandra.yaml"
  compgen -G "${dir}/lib/*.jar" >/dev/null || die "missing cassandra runtime jars under ${dir}/lib"

  pack_binary_tar "${base}" "${version}"
}

select_hadoop_dist_tar() {
  local version="$1"
  local dir="$2"
  local candidate
  candidate="$(find "${dir}/hadoop-dist/target" -maxdepth 1 -type f -name "hadoop-${version}*.tar.gz" ! -name "*-src.tar.gz" | head -n1)"
  [[ -n "${candidate}" ]] || die "cannot find built hadoop dist tar for ${version}"
  echo "${candidate}"
}

materialize_hadoop_runtime_from_dist() {
  local version="$1"
  local base="$2"
  local dist_tar="$3"
  local target="${base}/${version}"
  local tmp
  tmp="$(mktemp -d)"

  run_logged "extract_hadoop_dist_${version}" tar -xzf "${dist_tar}" -C "${tmp}"

  local root
  root="$(find "${tmp}" -mindepth 1 -maxdepth 1 -type d | head -n1)"
  [[ -n "${root}" ]] || die "cannot find extracted hadoop root for ${version}"

  rm -rf "${target}"
  mv "${root}" "${target}"
  rm -rf "${tmp}"
}

patch_hdfs_daemon() {
  local version="$1"
  local dir="$2"

  if [[ "${version}" == hadoop-2.* ]]; then
    cp -f "${UPFUZZ_ROOT}/src/main/resources/FsShellDaemon2.java" "${dir}/FsShellDaemon.java"
    run_logged "javac_hdfs_daemon_${version}" bash -lc "cd '${dir}' && '${JAVA8}/bin/javac' -d . -cp 'share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*' FsShellDaemon.java"
    if ! rg -q "dfsdaemon" "${dir}/bin/hdfs"; then
      run_logged "patch_hdfs_script_${version}" sed -i 's/elif \[ "\$COMMAND" = "dfs" \] ; then/elif [ "\$COMMAND" = "dfsdaemon" ] ; then\n  CLASS=org.apache.hadoop.fs.FsShellDaemon\n  HADOOP_OPTS="\$HADOOP_OPTS \$HADOOP_CLIENT_OPTS"\n&/' "${dir}/bin/hdfs"
    fi
  else
    cp -f "${UPFUZZ_ROOT}/src/main/resources/FsShellDaemon_trunk.java" "${dir}/FsShellDaemon.java"
    run_logged "javac_hdfs_daemon_${version}" bash -lc "cd '${dir}' && '${JAVA8}/bin/javac' -d . -cp 'share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*' FsShellDaemon.java"
    if ! rg -q "dfsdaemon" "${dir}/bin/hdfs"; then
      run_logged "patch_hdfs_script_${version}" sed -i 's/  case ${subcmd} in/&\n    dfsdaemon)\n      HADOOP_CLASSNAME="org.apache.hadoop.fs.FsShellDaemon"\n    ;;/' "${dir}/bin/hdfs"
    fi
  fi
}

build_hdfs_version() {
  local version="$1"
  local base="${PREBUILD_ROOT}/hdfs"
  local src_dir="${base}/${version}"
  local dist_tar

  extract_src_archive "hdfs" "${version}"

  if command -v protoc >/dev/null 2>&1; then
    log "Using protoc: $(protoc --version)"
  fi

  if [[ "${version}" == hadoop-2.* ]]; then
    # Hadoop 2.10.x dist assembly requires extra module outputs under
    # hdfs-native-client/hdfs-rbf and hadoop-yarn-project target dirs.
    run_logged "build_hdfs_${version}" bash -lc "cd '${src_dir}' && JAVA_HOME='${JAVA8}' PATH='/usr/bin:${JAVA8}/bin:${PATH}' MAVEN_OPTS='-Xmx6g' mvn -DskipTests -DskipITs -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Drat.skip=true -Dtar -Pdist clean package -pl hadoop-dist,hadoop-common-project/hadoop-nfs,hadoop-hdfs-project/hadoop-hdfs-httpfs,hadoop-hdfs-project/hadoop-hdfs-nfs,hadoop-hdfs-project/hadoop-hdfs-native-client,hadoop-hdfs-project/hadoop-hdfs-rbf,hadoop-yarn-project,hadoop-mapreduce-project,hadoop-tools/hadoop-tools-dist -am"
  else
    # Keep the build scope narrow on 3.x to avoid optional yarn catalog webapp
    # frontend/yarn-install steps that are unrelated to runtime dist layout.
    run_logged "build_hdfs_${version}" bash -lc "cd '${src_dir}' && JAVA_HOME='${JAVA8}' PATH='/usr/bin:${JAVA8}/bin:${PATH}' MAVEN_OPTS='-Xmx6g' mvn -DskipTests -DskipITs -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Drat.skip=true -Dtar -Pdist clean package -pl hadoop-dist,hadoop-common-project/hadoop-nfs,hadoop-hdfs-project/hadoop-hdfs-httpfs,hadoop-hdfs-project/hadoop-hdfs-nfs,hadoop-hdfs-project/hadoop-hdfs-native-client,hadoop-hdfs-project/hadoop-hdfs-rbf,hadoop-mapreduce-project,hadoop-tools/hadoop-tools-dist -am"
  fi

  dist_tar="$(select_hadoop_dist_tar "${version#hadoop-}" "${src_dir}")"
  materialize_hadoop_runtime_from_dist "${version}" "${base}" "${dist_tar}"

  local runtime_dir="${base}/${version}"
  mkdir -p "${runtime_dir}/share/hadoop/common/lib" "${runtime_dir}/share/hadoop/hdfs/lib"
  cp -f "${SSG_JAR}" "${runtime_dir}/share/hadoop/common/lib/ssgFatJar.jar"
  cp -f "${SSG_JAR}" "${runtime_dir}/share/hadoop/hdfs/lib/ssgFatJar.jar"

  patch_hdfs_daemon "${version}" "${runtime_dir}"
  verify_hdfs_network_trace "${version}" "${runtime_dir}"

  [[ -x "${runtime_dir}/bin/hdfs" ]] || die "missing hdfs binary: ${runtime_dir}/bin/hdfs"
  compgen -G "${runtime_dir}/share/hadoop/common/hadoop-common-*.jar" >/dev/null || die "missing hadoop-common jar in ${runtime_dir}"

  pack_binary_tar "${base}" "${version}"
}

pick_hbase_daemon() {
  local version="$1"
  local num="${version#hbase-}"
  local major="${num%%.*}"
  local rest="${num#*.}"
  local minor="${rest%%.*}"
  if (( major > 2 || (major == 2 && minor >= 4) )); then
    echo "hbase_daemon3.py"
  else
    echo "hbase_daemon2.py"
  fi
}

select_hbase_dist_tar() {
  local version="$1"
  local dir="$2"
  local candidate
  candidate="${dir}/hbase-assembly/target/hbase-${version}-bin.tar.gz"
  [[ -f "${candidate}" ]] || die "cannot find built hbase dist tar for ${version}: ${candidate}"
  echo "${candidate}"
}

materialize_hbase_runtime_from_dist() {
  local version="$1"
  local base="$2"
  local dist_tar="$3"
  local target="${base}/${version}"
  local tmp
  tmp="$(mktemp -d)"

  run_logged "extract_hbase_dist_${version}" tar -xzf "${dist_tar}" -C "${tmp}"

  local root
  root="$(find "${tmp}" -mindepth 1 -maxdepth 1 -type d | head -n1)"
  [[ -n "${root}" ]] || die "cannot find extracted hbase root for ${version}"

  rm -rf "${target}"
  mv "${root}" "${target}"
  rm -rf "${tmp}"
}

build_hbase_version() {
  local version="$1"
  local base="${PREBUILD_ROOT}/hbase"
  local src_dir="${base}/${version}"
  local java_home extra_profile=()
  local daemon

  extract_src_archive "hbase" "${version}"

  case "${version}" in
    hbase-3.*)
      java_home="${JAVA17}"
      extra_profile=("-Phadoop-3.0")
      ;;
    *)
      java_home="${JAVA8}"
      extra_profile=()
      ;;
  esac

  run_logged "build_hbase_${version}" bash -lc "cd '${src_dir}' && JAVA_HOME='${java_home}' PATH='${java_home}/bin:${PATH}' MAVEN_OPTS='-Xmx6g' mvn ${extra_profile[*]:-} -DskipTests -DskipITs -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Drat.skip=true -Denforcer.skip=true -Dskip.license.check=true -Dwarbucks.skip=true -pl hbase-assembly -am package assembly:single"

  local dist_tar
  dist_tar="$(select_hbase_dist_tar "${version#hbase-}" "${src_dir}")"
  materialize_hbase_runtime_from_dist "${version}" "${base}" "${dist_tar}"

  local runtime_dir="${base}/${version}"
  mkdir -p "${runtime_dir}/lib"
  cp -f "${SSG_JAR}" "${runtime_dir}/lib/ssgFatJar.jar"

  cp -f "${UPFUZZ_ROOT}/src/main/resources/hbase/compile-src/hbase-env.sh" "${runtime_dir}/conf/hbase-env.sh"
  if [[ "${version}" == hbase-3.* ]]; then
    cp -f "${UPFUZZ_ROOT}/src/main/resources/hbase/compile-src/hbase-env-jdk17.sh" "${runtime_dir}/conf/hbase-env.sh"
  fi

  daemon="$(pick_hbase_daemon "${version}")"
  cp -f "${UPFUZZ_ROOT}/src/main/resources/hbase/compile-src/${daemon}" "${runtime_dir}/bin/hbase_daemon.py"
  verify_hbase_network_trace "${version}" "${runtime_dir}"

  [[ -x "${runtime_dir}/bin/hbase" ]] || die "missing hbase binary: ${runtime_dir}/bin/hbase"
  compgen -G "${runtime_dir}/lib/hbase-*.jar" >/dev/null || die "missing hbase jars in ${runtime_dir}/lib"

  pack_binary_tar "${base}" "${version}"
}

should_run_target() {
  local key="$1"
  local selected="${TARGETS:-all}"
  if [[ "${selected}" == "all" ]]; then
    return 0
  fi
  case ",${selected}," in
    *",${key},"*) return 0 ;;
    *) return 1 ;;
  esac
}

main() {
  [[ -f "${SSG_JAR}" ]] || die "ssgFatJar.jar not found at ${SSG_JAR}"

  if should_run_target "cassandra"; then
    log "Building Cassandra binaries"
    build_cassandra_version "apache-cassandra-3.11.19"
    build_cassandra_version "apache-cassandra-4.1.10"
    build_cassandra_version "apache-cassandra-5.0.6"
  fi

  if should_run_target "hdfs"; then
    log "Building HDFS binaries"
    build_hdfs_version "hadoop-2.10.2"
    build_hdfs_version "hadoop-3.3.6"
    build_hdfs_version "hadoop-3.4.2"
  fi

  if should_run_target "hbase"; then
    log "Building HBase binaries"
    build_hbase_version "hbase-2.5.13"
    build_hbase_version "hbase-2.6.4"
    build_hbase_version "hbase-3.0.0-beta-1"
  fi

  log "Requested binary builds complete (TARGETS=${TARGETS:-all})"
}

main "$@"
