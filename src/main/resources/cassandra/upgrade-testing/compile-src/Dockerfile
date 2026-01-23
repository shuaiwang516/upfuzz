# FROM openjdk:8-jdk # Deprecated
FROM eclipse-temurin:8-jdk-focal

# 0. use tuna apt sources

RUN apt install apt-transport-https ca-certificates
# RUN mv /etc/apt/sources.list /etc/apt/sources.list.bak && \
#     echo "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bullseye main contrib non-free" >> /etc/apt/sources.list && \
#     echo "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bullseye-updates main contrib non-free" >> /etc/apt/sources.list && \
#     echo "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bullseye-backports main contrib non-free" >> /etc/apt/sources.list && \
#     echo "deb https://mirrors.tuna.tsinghua.edu.cn/debian-security bullseye-security main contrib non-free" >> /etc/apt/sources.list


# ENV http_proxy "http://172.17.0.1:2334"
# ENV https_proxy "http://172.17.0.1:2334"

# 1. General setup.

# Use supervisor to run multiple processes, in case the target system requires
# multiple processes. 
# Remember to open port 22 for sshd.
#RUN apt-get update && apt-get install -y --allow-unauthenticated openssh-server supervisor
RUN apt-get update && apt-get install -y openssh-server iproute2 net-tools iputils-ping supervisor vim ant maven python python3 python3-pip python-six psmisc

RUN pip3 install six wcwidth
RUN apt-get install iptables -y

ADD general-setup.sh /usr/local/bin/general-setup.sh
RUN sh /usr/local/bin/general-setup.sh
COPY org.jacoco.agent.rt.jar /org.jacoco.agent.rt.jar
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# 2. System setup.

# build locally
# COPY cassandra /cassandra
# WORKDIR /cassandra
# RUN ant
# COPY cqlsh_daemon.py /cassandra/bin/cqlsh_daemon.py

# likely invariants

COPY cqlsh_init.sh /cassandra/cqlsh_init.sh

# RUN pip3 install cassandra-driver

RUN pip3 install --only-binary=:all: cassandra-driver


# This file contains relatively permanent configurations.
# ADD cass-setup.sh /usr/local/bin/cass-setup.sh
# RUN sh /usr/local/bin/cass-setup.sh

# set CASSANDRA_HOME manually through docker shell
# ENV CASSANDRA_CONFIG /cassandra/conf

# This file contains all the runtime configurations (configurations that might
# need to be modified everytime Cassandra is run, e.g., logging level.)
ADD cassandra-clusternode.sh /usr/local/bin/cassandra-clusternode

# This can be overwritten in docker-compose.yaml by setting command
EXPOSE 22 9160
CMD ["/usr/bin/supervisord"]
