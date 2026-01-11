#!/bin/bash

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_18105.sh cass-18105 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_18105.sh cass-18105 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_18108.sh cass-18108 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_18108.sh cass-18108 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_19590.sh cass-19590 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_19590.sh cass-19590 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_19639.sh cass-2-3 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_19639.sh cass-2-3 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_19689.sh cass-19689 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_19689.sh cass-19689 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_20182.sh cass-2-3 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_cass_20182.sh cass-2-3 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hbase_28583.sh hbase-28583 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hbase_28583.sh hbase-28583 df_vd_s 

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hbase_29021.sh hbase-29021 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hbase_29021.sh hbase-29021 df_vd_s        

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hdfs_16984.sh hdfs-16984 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hdfs_16984.sh hdfs-16984 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hdfs_17219.sh hdfs-17219 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hdfs_17219.sh hdfs-17219 df_vd_s

bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hdfs_17686.sh hdfs-17686 base
bash artifact/bug-reproduction/trace/scripts/compute_avg.sh bin/check_hdfs_17686.sh hdfs-17686 df_vd_s
