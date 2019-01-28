#!/usr/bin/env bash

set -e # fail on errors
set -x # print commands

source shared.sh

# Step 1: Set up remote machine
ssh ${REMOTE_USER}@${REMOTE_HOST} mkdir -p ${REMOTE_DIR}/notebooks || true
# make sure to be in REMOTE_DIR in case there's a virtualenv there.
ssh ${REMOTE_USER}@${REMOTE_HOST} bash -c "pushd ${REMOTE_DIR}; pip install jep; popd" || true

# Step 2: Copy to remote machine
./assembleAndSend.sh

# Step 3: Run
if test "${INSTALL_ONLY}" -eq "1"; then
    echo "Not running polynote"
else
    ./runRemotely.sh
fi

