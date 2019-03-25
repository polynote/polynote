#!/usr/bin/env bash

DIR=$(dirname "$0")
source ${DIR}/shared.sh

if test -z ${POLYNOTE_DIST}; then
    echo "Couldn't find a Polynote Distribution to use! Please run ./make-distribution.sh or set the POLYNOTE_DIST variable."
    exit 1
fi

sendToRemote ${POLYNOTE_DIST}
