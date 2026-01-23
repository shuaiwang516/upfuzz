# Artifact Testing Report (not for reviewers)

3rd test status: all passed.

```bash
# kick the tires
cass [done]
hbase [done]
hdfs [done]

# state
state exploration [done]

# triggering time
triggering time [done]

# repo
cass-18105 [done]
cass-18108 [done]
cass-19590 [done]
cass-19591 [done]
cass-19623 [done]
cass-19639 [done]
cass-19689 [done]
cass-20182 [done]
hbase-28583 [done]
hbase-28812 [done]
hbase-28815 [done]
hbase-29021 [done]
hdfs-16984 [done]
hdfs-17219 [done]
hdfs-17686 [require multiple runs in reproduction mode]

# overhead
cass-2: [done]
cass-4: [done]
hbase [done]
hdfs-2: [done]
```

# Debug
```bash
# set remote url with ssh
git remote set-url origin git@github.com:zlab-purdue/upfuzz.git

# tag and push images to docker hub
docker tag \
  upfuzz_cassandra:apache-cassandra-2.2.19_apache-cassandra-3.0.30 \
  hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30
docker push hanke580/upfuzz-ae:cassandra-2.2.19_3.0.30

cd ~/project/upfuzz
bin/remove.sh
```