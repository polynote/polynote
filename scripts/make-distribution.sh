#!/usr/bin/env bash

# This script creates the Polynote distribution file

set -x
set -e

DIR=$(dirname "$0")

pushd ${DIR}/../
    root=`pwd`
    pushd polynote-frontend
        npm install
        npm run dist
    popd

    sbt 'project polynote-spark' 'set test in assembly := {}' 'assembly'

    distDir="dist"
    rm -r ${distDir}
    mkdir ${distDir}
    pushd ${distDir}
        cp ${root}/polynote-spark/target/scala-2.11/polynote-spark-assembly-0.1.0-SNAPSHOT.jar ./
        cp ${root}/config-template.yml ./
        cp ${root}/scripts/install-jep.sh ./
        cp ${root}/scripts/run.sh ./

        # this file isn't checked into git, but if it's present we'll put it into the zip file. It contains some deployment-specific config.
        cp ${root}/scripts/dist-config.yml ./config.yml || true
    popd

    distro="polynote-distribution.zip"
    zip ${distro} -r ${distDir}

    export POLYNOTE_DIST="`pwd`/${distro}"
    echo "Created Polynote distribution file: ${POLYNOTE_DIST}"

popd


