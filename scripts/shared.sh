#!/usr/bin/env bash

if test -z $REMOTE_HOST; then
    echo "Please set the REMOTE_HOST variable"
    exit 1
fi

REMOTE_USER=${REMOTE_USER:-root}
REMOTE_DIR=${REMOTE_DIR:-/root/polynote}
INSTALL_ONLY=${INSTALL_ONLY:-0}

