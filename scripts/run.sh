#!/usr/bin/env bash
set -x
set -e

DIR=$(dirname "$0")
actual_command=$(spark-submit --master local --deploy-mode client --class polynote.server.SparkServer ${DIR}/polynote-spark-assembly-0.1.0-SNAPSHOT.jar --printCommand 2>&1 | grep "SparkSubmit:" | grep -o '[^:]*$')
echo "${actual_command}"
bash -l -c "${actual_command}"