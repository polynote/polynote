#!/usr/bin/env bash

if test -z $REMOTE_HOST; then
    echo "Please set the REMOTE_HOST variable"
    exit 1
fi

REMOTE_USER=${REMOTE_USER:-root}
REMOTE_DIR=${REMOTE_DIR:-/root/polynote}
INSTALL_ONLY=${INSTALL_ONLY:-0}

function sendToRemote() {
    scp "$1" ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}
}

function runOnRemote() {
    ssh -t ${REMOTE_USER}@${REMOTE_HOST} "$1"
}