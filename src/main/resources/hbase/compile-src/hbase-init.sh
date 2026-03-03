#!/usr/bin/env bash
set -euo pipefail

# Copy format coverage files
if [ "$ENABLE_FORMAT_COVERAGE" = "true" ]; then
    # Copy the file to /tmp
    echo "Enable format coverage"
    cp "$HBASE_HOME/topObjects.json" /tmp/
    cp "$HBASE_HOME/serializedFields_alg1.json" /tmp/
    cp "$HBASE_HOME/comparableClasses.json" /tmp/ || true
    cp "$HBASE_HOME/modifiedEnums.json" /tmp/ || true
    cp "$HBASE_HOME/modifiedFields.json" /tmp/ || true
fi

# ENABLE_NET_COVERAGE
if [ "$ENABLE_NET_COVERAGE" = "true" ]; then
    # Copy the file to /tmp
    echo "Enable net coverage"
    cp "$HBASE_HOME/modifiedFields.json" /tmp/ || true
fi

if [[ -z $(grep -F "master" "/etc/hosts") ]];
then
        if [ -e "/etc/tmp_hosts" ]; then
          rm "/etc/tmp_hosts"
        fi
        touch /etc/tmp_hosts
        echo ${HADOOP_IP}"   master" >> /etc/tmp_hosts
        echo "master written to host"
        IP=$(hostname --ip-address | cut -f 1 -d ' ')
        IP_MASK=$(echo $IP | cut -d "." -f -3)
        HMaster_IP=$IP_MASK.2
        HRegion1_IP=$IP_MASK.3
        HRegion2_IP=$IP_MASK.4
        echo ${HMaster_IP}"   hmaster" >> /etc/tmp_hosts
        echo ${HRegion1_IP}"   hregion1" >> /etc/tmp_hosts
        echo ${HRegion2_IP}"   hregion2" >> /etc/tmp_hosts
        cat /etc/hosts >> /etc/tmp_hosts
        cat /etc/tmp_hosts | tee /etc/hosts > /dev/null
fi

mkdir -p ${HBASE_CONF}

bin=${HBASE_HOME}

cp -f ${bin}/conf/* ${HBASE_CONF}/
if [ ${CUR_STATUS} = "ORI" ]
then
    cp -f /test_config/oriconfig/* ${HBASE_CONF}/
else
    cp -f /test_config/upconfig/* ${HBASE_CONF}/
fi

export HBASE_ENV_INIT=
# export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64/
export HBASE_CONF_DIR=${HBASE_CONF}

# Connection to NN
while true; do
    /hadoop/hadoop-2.10.2/bin/hadoop fs -ls hdfs://master:8020/
    if [[ "$?" -eq 0 ]];
    then
        break
    fi
    sleep 5
done

# /bin/bash -c "/hbase/hbase-2.4.15/bin/start-hbase.sh"

# . "$bin"/bin/hbase-config.sh --config ${HBASE_CONF}

# HBASE-6504 - only take the first line of the output in case verbose gc is on
# distMode=`$bin/bin/hbase --config "$HBASE_CONF" org.apache.hadoop.hbase.util.HBaseConfTool hbase.cluster.distributed | head -n 1`

# if [ "$distMode" == 'false' ]
# then
#   "$bin"/bin/hbase-daemon.sh --config "${HBASE_CONF_DIR}" $commandToRun master
# else
#   "$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF_DIR}" $commandToRun zookeeper
#   "$bin"/bin/hbase-daemon.sh --config "${HBASE_CONF_DIR}" $commandToRun master
#   "$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF_DIR}" \
#     --hosts "${HBASE_REGIONSERVERS}" $commandToRun regionserver
#   "$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF_DIR}" \
#     --hosts "${HBASE_BACKUP_MASTERS}" $commandToRun master-backup
# fi

HBASE_REGIONSERVERS="${HBASE_REGIONSERVERS:-$HBASE_CONF/regionservers}"

get_hbase_zk_quorum() {
    awk '
        /<name>[[:space:]]*hbase.zookeeper.quorum[[:space:]]*<\/name>/ {
            want_value = 1
            next
        }
        want_value && match($0, /<value>[[:space:]]*([^<]+)[[:space:]]*<\/value>/, m) {
            print m[1]
            exit
        }
    ' "${HBASE_CONF}/hbase-site.xml" 2>/dev/null || true
}

get_local_hbase_alias() {
    local ip
    ip="$(hostname --ip-address | awk '{print $1}')"
    awk -v ip="${ip}" '
        $1 == ip {
            for (i = 2; i <= NF; i++) {
                if ($i ~ /^h(master|region[0-9]+)$/) {
                    print $i
                    exit
                }
            }
        }
    ' /etc/hosts 2>/dev/null || true
}

quorum_contains_alias() {
    local quorum="$1"
    local alias="$2"
    local entry
    local entries
    IFS=',' read -r -a entries <<< "${quorum}"
    for entry in "${entries[@]}"; do
        entry="$(echo "${entry}" | tr -d '[:space:]')"
        entry="${entry%%:*}"
        if [[ "${entry}" == "${alias}" ]]; then
            return 0
        fi
    done
    return 1
}

should_start_local_zookeeper() {
    if [[ "${IS_HMASTER}" == "true" ]]; then
        return 0
    fi

    local alias quorum
    alias="$(get_local_hbase_alias)"
    quorum="$(get_hbase_zk_quorum)"
    if [[ -z "${alias}" || -z "${quorum}" ]]; then
        return 1
    fi
    quorum_contains_alias "${quorum}" "${alias}"
}

wait_for_hbase_process() {
    local expected_jps="$1"
    local launcher_pid="${2:-}"

    for _ in $(seq 1 60); do
        if jps | grep -q "${expected_jps}"; then
            return 0
        fi
        if [[ -n "${launcher_pid}" ]] && ! kill -0 "${launcher_pid}" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    return 1
}

collect_hbase_service_pids() {
    local mode="${1:-all}"
    case "${mode}" in
        regionserver_only)
            jps -l | awk '/org\.apache\.hadoop\.hbase\.regionserver\.HRegionServer/ {print $1}'
            ;;
        all)
            jps -l | awk '/org\.apache\.hadoop\.hbase\.(master\.HMaster|regionserver\.HRegionServer|zookeeper\.HQuorumPeer)/ {print $1}'
            ;;
        *)
            echo "Unknown PID collection mode: ${mode}" >&2
            return 1
            ;;
    esac
}

stop_leftover_hbase_processes() {
    local mode="${1:-all}"
    local pids
    pids="$(collect_hbase_service_pids "${mode}" || true)"
    if [[ -z "${pids}" ]]; then
        return 0
    fi

    echo "Stopping leftover HBase JVMs (${mode}): ${pids}" >&2
    kill ${pids} 2>/dev/null || true

    for _ in $(seq 1 30); do
        pids="$(collect_hbase_service_pids "${mode}" || true)"
        if [[ -z "${pids}" ]]; then
            return 0
        fi
        sleep 1
    done

    echo "Force killing leftover HBase JVMs (${mode}): ${pids}" >&2
    kill -9 ${pids} 2>/dev/null || true
}

start_hbase_component() {
    local component="$1"
    local expected_jps="$2"
    local daemon_sh="${HBASE_HOME}/bin/hbase-daemon.sh"
    local daemon_py="${HBASE_HOME}/bin/hbase-daemon.py"
    local fallback_log="/var/log/hbase/${component}-fallback.log"

    if [[ -x "${daemon_sh}" ]]; then
        if "${daemon_sh}" --config "${HBASE_CONF}" start "${component}" >> "${fallback_log}" 2>&1 && \
            wait_for_hbase_process "${expected_jps}"; then
            return 0
        fi
    fi

    # Some bundles use hbase-daemon.py (dash) for service start/stop.
    if [[ -f "${daemon_py}" ]] && command -v python3 >/dev/null 2>&1; then
        if python3 "${daemon_py}" --config "${HBASE_CONF}" start "${component}" >> "${fallback_log}" 2>&1 && \
            wait_for_hbase_process "${expected_jps}"; then
            return 0
        fi
    fi

    # hbase_daemon.py (underscore) is the shell daemon in this repo; use hbase launcher for components.
    "${HBASE_HOME}/bin/hbase" --config "${HBASE_CONF}" "${component}" start >> "${fallback_log}" 2>&1 &
    local launcher_pid=$!

    if wait_for_hbase_process "${expected_jps}" "${launcher_pid}"; then
        return 0
    fi

    echo "Failed to start ${component} (expected ${expected_jps}). Check ${fallback_log}" >&2
    return 1
}

# Start up ZK first, then after some time, start up HMaster => Other RSs.

ZK_START_TIME=10
WAIT_FOR_HMASTER=10

LOCAL_ZK_REQUIRED="false"
if should_start_local_zookeeper; then
    LOCAL_ZK_REQUIRED="true"
fi

if [[ "${CUR_STATUS:-}" == "UP" ]]; then
    if [[ "${IS_HMASTER}" == "true" ]]; then
        stop_leftover_hbase_processes all
    elif [[ "${LOCAL_ZK_REQUIRED}" == "true" ]]; then
        # Keep local ZK alive only when this node belongs to configured quorum.
        stop_leftover_hbase_processes regionserver_only
    else
        # Ensure stray ZK processes do not interfere on nodes outside quorum.
        stop_leftover_hbase_processes all
    fi
fi

if [ ${IS_HMASTER} = "true" ]
then
    start_hbase_component zookeeper HQuorumPeer
    # Wait for ZK to start up
    sleep $ZK_START_TIME
    start_hbase_component master HMaster
else
    if [[ "${LOCAL_ZK_REQUIRED}" == "true" ]]; then
        if [[ "${CUR_STATUS:-}" != "UP" ]]; then
            start_hbase_component zookeeper HQuorumPeer
            # Wait for ZK to start up
            sleep ${ZK_START_TIME}
        else
            # On regionserver rolling upgrade, keep existing ZK if already alive.
            if ! jps -l | grep -q 'org.apache.hadoop.hbase.zookeeper.HQuorumPeer'; then
                start_hbase_component zookeeper HQuorumPeer
                sleep ${ZK_START_TIME}
            fi
        fi
    fi
    sleep ${WAIT_FOR_HMASTER}
    start_hbase_component regionserver HRegionServer
fi


#"$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF}" start zookeeper
#"$bin"/bin/hbase-daemon.sh --config "${HBASE_CONF}" start master
#
#
#"$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF}" \
#    --hosts "${HBASE_REGIONSERVERS}" start regionserver

# "$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF}" \
  #    --hosts "${HBASE_REGIONSERVERS}" stop regionserver
