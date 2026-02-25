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


# Start up ZK first, then after some time, start up HMaster => Other RSs.

ZK_START_TIME=10
WAIT_FOR_HMASTER=10

if [ ${IS_HMASTER} = "true" ]
then
    ${HBASE_HOME}/bin/hbase-daemon.sh --config "${HBASE_CONF}" start zookeeper
    # Wait for ZK to start up
    sleep $ZK_START_TIME
    ${HBASE_HOME}/bin/hbase-daemon.sh --config "${HBASE_CONF}" start master
else
    ${HBASE_HOME}/bin/hbase-daemon.sh --config "${HBASE_CONF}" start zookeeper
    # Wait for ZK to start up
    sleep ${ZK_START_TIME}
    sleep ${WAIT_FOR_HMASTER}
    ${HBASE_HOME}/bin/hbase-daemon.sh --config ${HBASE_CONF} start regionserver
fi


#"$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF}" start zookeeper
#"$bin"/bin/hbase-daemon.sh --config "${HBASE_CONF}" start master
#
#
#"$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF}" \
#    --hosts "${HBASE_REGIONSERVERS}" start regionserver

# "$bin"/bin/hbase-daemons.sh --config "${HBASE_CONF}" \
  #    --hosts "${HBASE_REGIONSERVERS}" stop regionserver
