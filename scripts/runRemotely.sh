#!/usr/bin/env bash

DIR=$(dirname "$0")
source ${DIR}/shared.sh

echo "Executing Polynote remotely. Note that if you lose connection to the server Polynote will stop running."

ssh -t ${REMOTE_USER}@${REMOTE_HOST} "cd ${REMOTE_DIR}; bash -l -c \"spark-submit \
    --driver-java-options '-Dlog4j.configuration=log4j.properties' \
    --ver 2.1.1-dra \
    --cluster trionplay_useast1prod \
    --conf spark.driver.maxResultSize=2048MB \
    --conf spark.sql.autoBroadcastJoinThreshold=-1 \
    polynote-spark-assembly-0.1.0-SNAPSHOT.jar \
    polynote.server.SparkServer\""