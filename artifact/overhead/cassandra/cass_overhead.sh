#!/bin/bash

bash artifact/overhead/cassandra/cass_2.sh true # with DF
bash artifact/overhead/cassandra/cass_2.sh false # without DF

bash artifact/overhead/cassandra/compute.sh