#!/usr/bin/env bash

set -e # fail on errors

DIR=$(dirname "$0")
source ${DIR}/shared.sh

cat << EOM
***************************************************************************************
NOTE: This script will install things and copy files on a remote machine!
It has access to everything that '${REMOTE_USER}' can do on '${REMOTE_HOST}'!

It will not install anything on the local machine though, so make sure you have all the
dependencies installed. See https://github.com/polynote/polynote#dependencies

Please confirm that you have read the script thoroughly and understand what it will do.
***************************************************************************************
EOM

read -p "I understand, please run commands remotely (yes/no): " choice
case "${choice}" in
    yes|Yes ) echo "Gotcha, proceeding";;
    no|No   ) echo "Good idea, check out the script and come back to me"; exit 1;;
    *       ) echo "Invalid input, please answer yes or no"; exit 2;;
esac

set -x # print subsequent commands

# Step 1: Set up remote machine
ssh ${REMOTE_USER}@${REMOTE_HOST} mkdir -p ${REMOTE_DIR}/notebooks || true
# make sure to be in REMOTE_DIR in case there's a virtualenv there.
ssh ${REMOTE_USER}@${REMOTE_HOST} bash -c "pushd ${REMOTE_DIR}; pip install jep jedi; popd" || true
ssh ${REMOTE_USER}@${REMOTE_HOST} ln -sf /usr/local/lib/python2.7/dist-packages/jep/libjep.so /usr/lib/libjep.so

# Step 2: Copy to remote machine
${DIR}/assembleAndSend.sh

# Step 3: Run
if test "${INSTALL_ONLY}" -ne "0"; then
    echo "Not running polynote"
else
    ${DIR}/runRemotely.sh
fi

