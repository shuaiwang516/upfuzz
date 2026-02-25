#!/usr/bin/env bash
set -euo pipefail

# Get running container's IP
IP=$(hostname --ip-address | cut -f 1 -d ' ')
if [ $# == 1 ]; then
    NAMENODE="$1,$IP"
else NAMENODE="$IP"; fi

# Change it to the target systems
ORI_VERSION=hadoop-3.3.6
UPG_VERSION=hadoop-3.4.2

# create necessary dirs (some version of cassandra cannot create these)
mkdir -p /var/log/hdfs
mkdir -p /var/lib/hdfs

if [[ ! -f "/var/log/.setup_conf" ]]; then
    if [ "$ENABLE_FORMAT_COVERAGE" = "true" ]; then
        # Copy the file to /tmp
        echo "Enable format coverage"
        cp "$HADOOP_HOME/topObjects.json" /tmp/
        cp "$HADOOP_HOME/serializedFields_alg1.json" /tmp/
        cp "$HADOOP_HOME/comparableClasses.json" /tmp/ || true
        cp "$HADOOP_HOME/modifiedEnums.json" /tmp/ || true
        cp "$HADOOP_HOME/modifiedFields.json" /tmp/ || true
    fi

    # ENABLE_NET_COVERAGE
    if [ "$ENABLE_NET_COVERAGE" = "true" ]; then
        # Copy the file to /tmp
        echo "Enable net coverage"
        cp "${HADOOP_HOME:-}/modifiedFields.json" /tmp/ || true
    fi

    echo "copy hadoop dir and format configurations"
    for VERSION in ${ORI_VERSION} ${UPG_VERSION}; do
        mkdir /etc/${VERSION}
        cp -r /hdfs/${VERSION}/etc /etc/${VERSION}/
        cp /hadoop-config/* /etc/${VERSION}/etc/hadoop/

        CONFIG="/etc/${VERSION}/etc/hadoop/"
        if [[ $VERSION == "${ORI_VERSION}" ]]; then
            cp /test_config/oriconfig/* ${CONFIG}/
        fi
        if [[ $VERSION == "${UPG_VERSION}" ]]; then
            cp /test_config/upconfig/* ${CONFIG}/
        fi

        echo "export JAVA_HOME=\/usr\/lib\/jvm\/java-8-openjdk-amd64\/" >> ${CONFIG}/hadoop-env.sh
        echo "export HDFS_NAMENODE_USER=\"root\"" >> ${CONFIG}/hadoop-env.sh
        echo "export HDFS_DATANODE_USER=\"root\"" >> ${CONFIG}/hadoop-env.sh
        echo "export HDFS_SECONDARYNAMENODE_USER=\"root\"" >> ${CONFIG}/hadoop-env.sh

        echo "export HADOOP_LOG_DIR=/var/log/hdfs" >> ${CONFIG}/hadoop-env.sh

        echo "export HADOOP_CLASSPATH=\${HADOOP_CLASSPATH}:/hdfs/${VERSION}" >> ${CONFIG}/hadoop-env.sh

        echo datanode1 > ${CONFIG}/slaves
        echo datanode2 >> ${CONFIG}/slaves

        echo datanode1 > ${CONFIG}/workers
        echo datanode2 >> ${CONFIG}/workers

        # always configure a 3 node HDFS cluster, .3/.4 is the data node
    done
    echo "setup done"
    touch "/var/log/.setup_conf"
fi


IP_MASK=$(echo $IP | cut -d "." -f -3)
HDFS_NAMENODE=$IP_MASK.2
HDFS_SECONDARY_NAMENODE=$IP_MASK.3

if [[ -z $(grep -F "master" "/etc/hosts") ]];
then
        echo "$HDFS_NAMENODE    master" >> /etc/hosts
        echo "$IP_MASK.3    secondarynn" >> /etc/hosts
        echo "$IP_MASK.4    datanode1" >> /etc/hosts
        echo "$IP_MASK.5    datanode2" >> /etc/hosts

        # echo "HADOOP_HOME=$HADOOP_HOME" >> ~/.bashrc
        # echo "PATH=\${PATH}:\${HADOOP_HOME}/bin:\${HADOOP_HOME}/sbin" >> ~/.bashrc
        # source ~/.bashrc
fi

#HADOOP_HOME=/etc/$ORI_VERSION
#PATH=${PATH}:${HADOOP_HOME}/bin:${HADOOP_HOME}/sbin

# Some Hadoop distributions require these homes to be explicitly set.
export HADOOP_COMMON_HOME="${HADOOP_HOME}"
export HADOOP_HDFS_HOME="${HADOOP_HOME}"
export HADOOP_MAPRED_HOME="${HADOOP_HOME}"
export HADOOP_YARN_HOME="${HADOOP_HOME}"
export PATH="${PATH}:${HADOOP_HOME}/bin:${HADOOP_HOME}/sbin"

echo "Starting HDFS on $IP..."

if [[ "$IP" == "$HDFS_NAMENODE" ]];
then
        if [[ ! -f /var/log/hdfs/.formatted ]];
        then
                echo "formatting namenode"
                $HADOOP_HOME/bin/hdfs namenode -format
                touch /var/log/hdfs/.formatted
        fi

        splitArr=(${HADOOP_HOME//\// })
        CUR_VERSION=${splitArr[1]}
        echo "cur version = $CUR_VERSION"
        echo "org version = $ORI_VERSION"
        echo "up  version = $UPG_VERSION"
        if [[ $CUR_VERSION == $ORI_VERSION ]];
        then
                            echo "start up old version $HADOOP_HOME"
                $HADOOP_HOME/sbin/hadoop-daemon.sh start namenode
        else
                            echo "start up new version $HADOOP_HOME"
                $HADOOP_HOME/sbin/hadoop-daemon.sh start namenode -rollingUpgrade started
        fi
elif [[ "$IP" == "$HDFS_SECONDARY_NAMENODE" ]];
then
        $HADOOP_HOME/sbin/hadoop-daemon.sh start secondarynamenode
else
        $HADOOP_HOME/sbin/hadoop-daemon.sh start datanode
fi
