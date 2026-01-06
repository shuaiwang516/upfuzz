
hdfs_repo_func() {
  # $1: BUG_ID
  # $2: SPECIAL_CONFIG (true/false)

  local BUG_ID=$1
  local SPECIAL_CONFIG=$2
  local SYSTEM="HDFS"
  local SYSTEM_SHORT="hdfs"
  local UPFUZZ_DIR=~/project/upfuzz

  if [ ! -d "$UPFUZZ_DIR" ]; then
    echo "Directory $UPFUZZ_DIR does not exist! Please check your setup and make sure you have cloned the upfuzz repository and put it under ~/project/upfuzz."
    exit 1
  fi

  local ORI_VERSION=2.10.2
  local UP_VERSION=3.3.6

  cd $UPFUZZ_DIR

  mkdir -p prebuild/hdfs
  cd prebuild/hdfs

  sudo rm -rf hadoop-$ORI_VERSION hadoop-$ORI_VERSION.tar.gz
  wget https://github.com/zlab-purdue/upfuzz/releases/download/hadoop/hadoop-"$ORI_VERSION".tar.gz
  tar -xzvf hadoop-"$ORI_VERSION".tar.gz > /dev/null

  sudo rm -rf hadoop-$UP_VERSION hadoop-$UP_VERSION.tar.gz
  wget https://github.com/zlab-purdue/upfuzz/releases/download/hadoop/hadoop-"$UP_VERSION".tar.gz
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

  docker pull hanke580/upfuzz-ae:hdfs-${ORI_VERSION}_${UP_VERSION}
  docker tag \
    hanke580/upfuzz-ae:hdfs-${ORI_VERSION}_${UP_VERSION} \
    upfuzz_hdfs:hadoop-${ORI_VERSION}_hadoop-${UP_VERSION}

  cd ${UPFUZZ_DIR}
  ./gradlew copyDependencies > /dev/null
  ./gradlew :spotlessApply build > /dev/null

  # copy config and triggering commands
  cd ${UPFUZZ_DIR}
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/hdfs_config.json hdfs_config.json
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/commands.txt examplecase/commands.txt
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/validcommands.txt examplecase/validcommands.txt

  # Reproduction run
  tmux kill-session -t 0
  tmux new-session -d -s 0 \; split-window -v \;
  tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hdfs_config.json > server.log' C-m \;
  tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 hdfs_config.json' C-m

  # Clean after one test (< 2 minutes)
  echo "Waiting for test completion (2 minutes)..."
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
  cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force;

  # check failure reports
  ls failure
  echo "--------------------------------"
  echo
  bin/check_${SYSTEM_SHORT}_${BUG_ID}.sh
}

hdfs_repo_func "$1" "$2"