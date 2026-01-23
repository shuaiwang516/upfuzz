
hdfs_repo_func() {
  # $1: FORMAT (true/false)

  local FORMAT=$1
  local SYSTEM="HDFS"
  local SYSTEM_SHORT="hdfs"
  local UPFUZZ_DIR=$PWD

  if [ ! -d "$UPFUZZ_DIR" ]; then
    echo "Directory $UPFUZZ_DIR does not exist! Please check your setup and make sure you have cloned the upfuzz repository."
    exit 1
  fi

  local ORI_VERSION=2.10.2
  local UP_VERSION=3.3.6

  cd $UPFUZZ_DIR

  mkdir -p prebuild/hdfs
  cd prebuild/hdfs

  sudo rm -rf hadoop-$ORI_VERSION hadoop-2.10.2-INST.tar.gz
  wget -q https://github.com/zlab-purdue/upfuzz/releases/download/inst/hadoop-2.10.2-INST.tar.gz
  tar -xzvf hadoop-2.10.2-INST.tar.gz > /dev/null

  sudo rm -rf hadoop-$UP_VERSION hadoop-$UP_VERSION.tar.gz
  wget -q https://github.com/zlab-purdue/upfuzz/releases/download/hadoop/hadoop-"$UP_VERSION".tar.gz
  tar -xzvf hadoop-"$UP_VERSION".tar.gz > /dev/null

  cd ${UPFUZZ_DIR}
  # old version hdfs daemon
  cp $UPFUZZ_DIR/src/main/resources/FsShellDaemon2.java $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$ORI_VERSION"/FsShellDaemon.java
  cd $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$ORI_VERSION"/
  /usr/lib/jvm/java-8-openjdk-amd64/bin/javac -d . -cp "share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*" FsShellDaemon.java
  sed -i "s/elif \[ \"\$COMMAND\" = \"dfs\" \] ; then/elif [ \"\$COMMAND\" = \"dfsdaemon\" ] ; then\n  CLASS=org.apache.hadoop.fs.FsShellDaemon\n  HADOOP_OPTS=\"\$HADOOP_OPTS \$HADOOP_CLIENT_OPTS\"\n&/" bin/hdfs

  # new version hdfs daemon
  cp $UPFUZZ_DIR/src/main/resources/FsShellDaemon_trunk.java $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$UP_VERSION"/FsShellDaemon.java
  cd $UPFUZZ_DIR/prebuild/hdfs/hadoop-"$UP_VERSION"/
  /usr/lib/jvm/java-8-openjdk-amd64/bin/javac -d . -cp "share/hadoop/hdfs/*:share/hadoop/common/*:share/hadoop/common/lib/*" FsShellDaemon.java
  sed -i "s/  case \${subcmd} in/&\n    dfsdaemon)\n      HADOOP_CLASSNAME=\"org.apache.hadoop.fs.FsShellDaemon\"\n    ;;/" bin/hdfs

  # ===== DF =====
  cd $UPFUZZ_DIR
  cp configInfo/hadoop-${ORI_VERSION}/* prebuild/hdfs/hadoop-${ORI_VERSION}/
  cp lib/ssgFatJar.jar prebuild/hdfs/hadoop-${ORI_VERSION}/share/hadoop/common/lib/
  cp lib/ssgFatJar.jar prebuild/hdfs/hadoop-${ORI_VERSION}/share/hadoop/hdfs/lib/
  # ===== DF =====

  docker pull hanke580/upfuzz-ae:hdfs-${ORI_VERSION}_${UP_VERSION} > /dev/null
  docker tag \
    hanke580/upfuzz-ae:hdfs-${ORI_VERSION}_${UP_VERSION} \
    upfuzz_hdfs:hadoop-${ORI_VERSION}_hadoop-${UP_VERSION}

  cd ${UPFUZZ_DIR}
  ./gradlew copyDependencies > /dev/null
  ./gradlew :spotlessApply build > /dev/null

  # copy config and triggering commands
  cd ${UPFUZZ_DIR}
  if [ "$FORMAT" == "true" ]; then
    cp artifact/overhead/hdfs/HDFS-17219-config-format-vd-static.json hdfs_config.json
  else
    cp artifact/overhead/hdfs/HDFS-17219-config-normal.json hdfs_config.json
  fi
  cp artifact/overhead/hdfs/commands.txt examplecase/commands.txt
  cp artifact/overhead/hdfs/validcommands.txt examplecase/validcommands.txt

  # Reproduction run
  tmux kill-session -t 0
  tmux new-session -d -s 0 \; split-window -v \;
  if [ "$FORMAT" == "true" ]; then
    tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hdfs_config.json > server_format.log' C-m \;
    tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 hdfs_config.json > client_format.log' C-m
  else
    tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hdfs_config.json > server_normal.log' C-m \;
    tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 hdfs_config.json > client_normal.log' C-m
  fi

  # Clean after one test (< 2 minutes)
  echo "Waiting for test completion ..."
  total=240
  for ((i=1; i<=total; i++)); do
    percent=$((i * 100 / total))
    bar_length=50
    filled_length=$((percent * bar_length / 100))
    
    # Create progress bar
    bar=""
    for ((j=0; j<filled_length; j++)); do bar+="█"; done
    for ((j=filled_length; j<bar_length; j++)); do bar+="░"; done
    
    remaining=$((total - i))
    printf "\r[%s] %d%% - %02d:%02d remaining" "$bar" "$percent" $((remaining/60)) $((remaining%60))
    sleep 1
  done
  echo -e "\nTest completed, starting cleanup..."
  cd $UPFUZZ_DIR; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force > /dev/null

  echo "--------------------------------"
  echo
}

hdfs_repo_func "$1"