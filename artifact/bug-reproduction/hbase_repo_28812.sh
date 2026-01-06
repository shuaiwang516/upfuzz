hbase_repo_func() {
  # $1: BUG_ID
  # $2: SPECIAL_CONFIG (true/false)

  local BUG_ID=$1
  local SPECIAL_CONFIG=${2:-false}
  local SYSTEM="HBASE"
  local SYSTEM_SHORT="hbase"
  local UPFUZZ_DIR=~/project/upfuzz

  if [ ! -d "$UPFUZZ_DIR" ]; then
    echo "Directory $UPFUZZ_DIR does not exist! Please check your setup and make sure you have cloned the upfuzz repository and put it under ~/project/upfuzz."
    exit 1
  fi

  local ORI_VERSION=2.6.0
  local UP_VERSION=3.0.0

  cd $UPFUZZ_DIR
  mkdir -p $UPFUZZ_DIR/prebuild/hadoop
  cd $UPFUZZ_DIR/prebuild/hadoop

  sudo rm -rf hadoop-2.10.2 hadoop-2.10.2.tar.gz
  wget -q https://github.com/zlab-purdue/upfuzz/releases/download/hadoop/hadoop-2.10.2.tar.gz
  tar -xzvf hadoop-2.10.2.tar.gz > /dev/null
  cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/core-site.xml $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f
  cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/hdfs-site.xml $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f
  cp $UPFUZZ_DIR/src/main/resources/hdfs/hbase-pure/hadoop-env.sh $UPFUZZ_DIR/prebuild/hadoop/hadoop-2.10.2/etc/hadoop/ -f

  cd $UPFUZZ_DIR
  mkdir -p $UPFUZZ_DIR/prebuild/hbase
  cd $UPFUZZ_DIR/prebuild/hbase

  sudo rm -rf hbase-$ORI_VERSION hbase-$ORI_VERSION-bin.tar.gz
  wget -q https://github.com/zlab-purdue/upfuzz/releases/download/hbase/hbase-$ORI_VERSION-bin.tar.gz
  tar -xzvf hbase-"$ORI_VERSION"-bin.tar.gz > /dev/null

  sudo rm -rf hbase-$UP_VERSION hbase-3.0.0-beta-2-a030e809.tar.gz
  wget -q https://github.com/zlab-purdue/upfuzz/releases/download/hbase/hbase-3.0.0-beta-2-a030e809.tar.gz
  tar -xzvf hbase-3.0.0-beta-2-a030e809.tar.gz > /dev/null

  cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase-env.sh $UPFUZZ_DIR/prebuild/hbase/hbase-$ORI_VERSION/conf/ -f
  cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase-env.sh $UPFUZZ_DIR/prebuild/hbase/hbase-$UP_VERSION/conf/ -f

  # If testing 3.0.0
  cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase-env-jdk17.sh $UPFUZZ_DIR/prebuild/hbase/hbase-$UP_VERSION/conf/hbase-env.sh -f

  # for hbase version >= 2.4.0, use hbase_daemon3.py
  # for hbase version < 2.4.0, use hbase_daemon2.py
  cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase_daemon3.py $UPFUZZ_DIR/prebuild/hbase/hbase-$ORI_VERSION/bin/hbase_daemon.py
  cp $UPFUZZ_DIR/src/main/resources/hbase/compile-src/hbase_daemon3.py $UPFUZZ_DIR/prebuild/hbase/hbase-$UP_VERSION/bin/hbase_daemon.py

  docker pull hanke580/upfuzz-ae:hbase-${ORI_VERSION}_${UP_VERSION}
  docker tag \
    hanke580/upfuzz-ae:hbase-${ORI_VERSION}_${UP_VERSION} \
    upfuzz_hbase:hbase-${ORI_VERSION}_hbase-${UP_VERSION}

  docker pull hanke580/upfuzz-ae:hdfs-2.10.2
  docker tag hanke580/upfuzz-ae:hdfs-2.10.2 \
    upfuzz_hdfs:hadoop-2.10.2

  cd ${UPFUZZ_DIR}
  ./gradlew copyDependencies > /dev/null
  ./gradlew :spotlessApply build > /dev/null

  # copy config and triggering commands
  cd ${UPFUZZ_DIR}
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/hbase_config.json hbase_config.json
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/commands.txt examplecase/commands.txt
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/validcommands.txt examplecase/validcommands.txt

  # special config (only for this bug)
  if [ "$SPECIAL_CONFIG" == "true" ]; then
    cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/hbase_ori.yaml prebuild/hbase/hbase-${ORI_VERSION}/conf/hbase.yaml
    cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/hbase_up.yaml prebuild/hbase/hbase-${UP_VERSION}/conf/hbase.yaml
  fi

  # Reproduction run
  tmux kill-session -t 0
  tmux new-session -d -s 0 \; split-window -v \;
  tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh hbase_config.json > server.log' C-m \;
  tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 hbase_config.json' C-m

  # Clean after one test (< 2 minutes)
  echo "Waiting for test completion (2 minutes)..."
  total=480
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
  cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force; > /dev/null

  # check failure reports
  ls failure
  echo "--------------------------------"
  echo
  bin/check_${SYSTEM_SHORT}_${BUG_ID}.sh
}

hbase_repo_func "$1" "$2"