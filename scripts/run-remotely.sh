#!/usr/bin/env bash

DIR=$(dirname "$0")
source ${DIR}/shared.sh

runOnRemote "cd ${REMOTE_DIR}; unzip -j ${REMOTE_DIR}/polynote-distribution.zip"
runOnRemote "bash -l -c 'cd ${REMOTE_DIR}; ./run.sh'"
