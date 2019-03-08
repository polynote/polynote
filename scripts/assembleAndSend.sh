#!/usr/bin/env bash
set -x
set -e

DIR=$(dirname "$0")
source ${DIR}/shared.sh

pushd ${DIR}/../ # back to root
    pushd polynote-frontend
        npm run build
    popd

    sbt 'project polynote-spark' 'assembly'
    scp ./polynote-spark/target/scala-2.11/polynote-spark-assembly-0.1.0-SNAPSHOT.jar ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}
    scp ./config.yml ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR} || true
popd


