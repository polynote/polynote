#!/usr/bin/env bash

DIR=$(dirname "$0")
source ${DIR}/shared.sh

runOnRemote "tmux new 'cd ${REMOTE_DIR}; ./run.sh'"
