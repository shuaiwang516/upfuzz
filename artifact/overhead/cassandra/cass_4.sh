cass_repo_func() {
  # $1: BUG_ID
  # $2: SPECIAL_CONFIG (true/false)

  local BUG_ID=$1
  local SPECIAL_CONFIG=$2
  local SYSTEM="CASSANDRA"
  local SYSTEM_SHORT="cass"
  local UPFUZZ_DIR=~/project/upfuzz

  if [ ! -d "$UPFUZZ_DIR" ]; then
    echo "Directory $UPFUZZ_DIR does not exist! Please check your setup and make sure you have cloned the upfuzz repository and put it under ~/project/upfuzz."
    exit 1
  fi

  local ORI_VERSION=4.1.6
  local UP_VERSION=5.0.2

  cd $UPFUZZ_DIR

  mkdir -p prebuild/cassandra
  cd prebuild/cassandra

  if [ ! -d "apache-cassandra-$ORI_VERSION" ]; then
    wget -q https://github.com/zlab-purdue/upfuzz/releases/download/cassandra/apache-cassandra-4.1.6-bin.tar.gz
    tar -xzvf apache-cassandra-"$ORI_VERSION"-bin.tar.gz > /dev/null
  fi
  if [ ! -d "apache-cassandra-$UP_VERSION" ]; then
    wget -q https://github.com/zlab-purdue/upfuzz/releases/download/cassandra/apache-cassandra-5.0.2-bin.tar.gz
    tar -xzvf apache-cassandra-"$UP_VERSION"-bin.tar.gz > /dev/null
  fi

  cd ${UPFUZZ_DIR}
  cp src/main/resources/cqlsh_daemon4.py prebuild/cassandra/apache-cassandra-"$ORI_VERSION"/bin/cqlsh_daemon.py
  cp src/main/resources/cqlsh_daemon5.py  prebuild/cassandra/apache-cassandra-"$UP_VERSION"/bin/cqlsh_daemon.py

  docker pull hanke580/upfuzz-ae:cassandra-${ORI_VERSION}_${UP_VERSION}
  docker tag \
    hanke580/upfuzz-ae:cassandra-${ORI_VERSION}_${UP_VERSION} \
    upfuzz_cassandra:apache-cassandra-${ORI_VERSION}_apache-cassandra-${UP_VERSION}

  cd ${UPFUZZ_DIR}
  ./gradlew copyDependencies > /dev/null
  ./gradlew :spotlessApply build > /dev/null

  # copy config and triggering commands
  cd ${UPFUZZ_DIR}
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/config.json config.json
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/commands.txt examplecase/commands.txt
  cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/validcommands.txt examplecase/validcommands.txt

  # special config (only for this bug)
  if [ "$SPECIAL_CONFIG" == "true" ]; then
    cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/cassandra_ori.yaml prebuild/cassandra/apache-cassandra-${ORI_VERSION}/conf/cassandra.yaml
    cp artifact/bug-reproduction/bugs/${SYSTEM}-${BUG_ID}/cassandra_up.yaml prebuild/cassandra/apache-cassandra-${UP_VERSION}/conf/cassandra.yaml
  fi

  # Reproduction run
  tmux kill-session -t 0
  tmux new-session -d -s 0 \; split-window -v \;
  tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh config.json > server.log' C-m \;
  tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 config.json > client.log' C-m

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
  cd ~/project/upfuzz; sudo chmod 777 /var/run/docker.sock; bin/clean.sh --force > /dev/null

  # check failure reports
  ls failure
  echo "--------------------------------"
  echo
  bin/check_${SYSTEM_SHORT}_${BUG_ID}.sh
}

cass_repo_func "$1" "$2"