cass_repo_func() {
  # $1: FORMAT (true/false)

  local FORMAT=$1
  local SYSTEM="CASSANDRA"
  local SYSTEM_SHORT="cass"
  local UPFUZZ_DIR=~/project/upfuzz

  if [ ! -d "$UPFUZZ_DIR" ]; then
    echo "Directory $UPFUZZ_DIR does not exist! Please check your setup and make sure you have cloned the upfuzz repository and put it under ~/project/upfuzz."
    exit 1
  fi

  local ORI_VERSION=2.2.19
  local UP_VERSION=3.0.30

  cd $UPFUZZ_DIR

  mkdir -p prebuild/cassandra
  cd prebuild/cassandra

  if [ ! -d "apache-cassandra-$ORI_VERSION" ]; then
    wget -q https://github.com/zlab-purdue/upfuzz/releases/download/inst/apache-cassandra-2.2.19-INST.tar.gz
    tar -xzvf apache-cassandra-2.2.19-INST.tar.gz > /dev/null
  fi
  if [ ! -d "apache-cassandra-$UP_VERSION" ]; then
    wget -q https://github.com/zlab-purdue/upfuzz/releases/download/cassandra/apache-cassandra-3.0.30-bin.tar.gz
    tar -xzvf apache-cassandra-"$UP_VERSION"-bin.tar.gz > /dev/null
  fi

  cd ${UPFUZZ_DIR}
  cp src/main/resources/cqlsh_daemon2.py prebuild/cassandra/apache-cassandra-"$ORI_VERSION"/bin/cqlsh_daemon.py
  cp src/main/resources/cqlsh_daemon2.py  prebuild/cassandra/apache-cassandra-"$UP_VERSION"/bin/cqlsh_daemon.py

  # ===== DF =====
  cd ${UPFUZZ_DIR}
  cp configInfo/apache-cassandra-${ORI_VERSION}/* prebuild/cassandra/apache-cassandra-${ORI_VERSION}
  cp lib/ssgFatJar.jar prebuild/cassandra/apache-cassandra-${ORI_VERSION}/lib/ssgFatJar.jar
  # ===== DF =====

  docker pull hanke580/upfuzz-ae:cassandra-${ORI_VERSION}_${UP_VERSION} > /dev/null
  docker tag \
    hanke580/upfuzz-ae:cassandra-${ORI_VERSION}_${UP_VERSION} \
    upfuzz_cassandra:apache-cassandra-${ORI_VERSION}_apache-cassandra-${UP_VERSION}

  cd ${UPFUZZ_DIR}
  ./gradlew copyDependencies > /dev/null
  ./gradlew :spotlessApply build > /dev/null

  # ===== Config and commands =====
  # copy config and triggering commands
  cd ${UPFUZZ_DIR}
  if [ "$FORMAT" == "true" ]; then
    cp artifact/overhead/cassandra/CASSANDRA-19623-config-format-vd-static.json config.json
  else
    cp artifact/overhead/cassandra/CASSANDRA-19623-config-normal.json config.json
  fi

  cp artifact/overhead/cassandra/commands.txt examplecase/commands.txt
  cp artifact/overhead/cassandra/validcommands.txt examplecase/validcommands.txt
  # ===== Config and commands =====

  # Reproduction run
  tmux kill-session -t 0
  tmux new-session -d -s 0 \; split-window -v \;
  if [ "$FORMAT" == "true" ]; then
    tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh config.json > server_format.log' C-m \;
    tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 config.json > client_format.log' C-m
  else
    tmux send-keys -t 0:0.0 C-m 'bin/start_server.sh config.json > server_normal.log' C-m \;
    tmux send-keys -t 0:0.1 C-m 'sleep 2; bin/start_clients.sh 1 config.json > client_normal.log' C-m
  fi

  # Clean after one test (< 2 minutes)
  echo "Waiting for test completion ..."
  total=120
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

  echo "--------------------------------"
  echo
}

cass_repo_func "$1"