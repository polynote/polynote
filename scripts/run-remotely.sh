#!/usr/bin/env bash

DIR=$(dirname "$0")
source ${DIR}/shared.sh

runOnRemote "unzip -j ${REMOTE_DIR}/polynote-distribution.zip"
runOnRemote "tmux new 'cd ${REMOTE_DIR}; ./run.sh'"
