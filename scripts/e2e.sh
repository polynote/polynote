#!/usr/bin/env bash

DIR=$(dirname "$0")

source ${DIR}/shared.sh

if test -z $NO_MSG; then
    cat << EOM
***************************************************************************************
NOTE: This script will install things and copy files on a remote machine!
It has access to everything that '${REMOTE_USER}' can do on '${REMOTE_HOST}'!

It is intended to be used by Polynote maintainers, users should get Polynote via normal distribution channels.

Please confirm that you have read the script thoroughly and understand what it will do.

You can turn off this message by setting NO_MSG=1
***************************************************************************************
EOM

    read -p "I understand, please run commands remotely (yes/no): " choice
    case "${choice}" in
        yes|Yes ) echo "Gotcha, proceeding";;
        no|No   ) echo "Good idea, check out the script and come back to me"; exit 1;;
        *       ) echo "Invalid input, please answer yes or no"; exit 2;;
    esac
fi



set -x # print subsequent commands

source ${DIR}/make-publish-distribution.sh

runOnRemote "unzip -j ${REMOTE_DIR}/polynote-distribution.zip"
source ${DIR}/run-remotely.sh

