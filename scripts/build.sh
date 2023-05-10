#!/usr/bin/env bash
#
# Build and push Docker images for Polynote.
# TODO DATAPLT-2330: Call from butler
# TODO DATAPLT-2332: Better support local building needs

# Build and push Docker image with a commit and branch tag
set -e

if [ -z "$GIT_SHA" ]; then
    echo "Environment variable \$GIT_SHA expected to be set (by Butler)"
    exit 1
fi

if [ -z "$GIT_BRANCH" ]; then
    echo "Environment variable \$GIT_BRANCH expected to be set (by Butler)"
    exit 1
fi

REPO="docker.strava.com/strava/polynote"
COMMIT_TAG="$REPO:$GIT_SHA"


# only create and push a new image if we are merging to main
if [[ "$GIT_BRANCH" == "main" ]]; then
    docker build -t strava/polynote:latest -f docker/dev/Dockerfile .

    BRANCH_TAG="$REPO:$GIT_BRANCH"
    docker tag strava/polynote:latest $COMMIT_TAG

    docker push $COMMIT_TAG
fi
