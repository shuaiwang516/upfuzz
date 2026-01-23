# FROM openjdk:8-jdk # Deprecated
FROM eclipse-temurin:8-jdk-focal

RUN apt install apt-transport-https ca-certificates
RUN apt-get update && apt-get install -y openssh-server iproute2 net-tools iputils-ping supervisor vim ant maven python python3 python3-pip python-six psmisc

RUN pip3 install six
RUN apt-get install iptables -y

ADD general-setup.sh /usr/local/bin/general-setup.sh
RUN sh /usr/local/bin/general-setup.sh
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# IMPORTANT: NEVER REMOVE THE COMMENT FROM THE FOLLOWING LINE, NEEDED FOR INITIAL TEST
# 1. Put code in it
COPY apache-cassandra-3.0.15 /cassandra/apache-cassandra-3.0.15
COPY apache-cassandra-2.2.8 /cassandra/apache-cassandra-2.2.8

# 2. System setup.
RUN pip3 install cassandra-driver
ADD cassandra-clusternode.sh /usr/local/bin/cassandra-clusternode

# This can be overwritten in docker-compose.yaml by setting command
EXPOSE 22 9160
CMD ["/usr/bin/supervisord"]
