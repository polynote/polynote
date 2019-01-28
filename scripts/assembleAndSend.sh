#!/usr/bin/env bash
set -x
set -e

source shared.sh

pushd polynote-frontend
	npm run build
popd

sbt 'project polynote-spark' 'assembly'
# Step 3: make code changes and
scp ./polynote-spark/target/scala-2.11/polynote-spark-assembly-0.1.0-SNAPSHOT.jar ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}


