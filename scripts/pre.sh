#!/usr/bin/env bash

if [[ "$GIT_BRANCH" == "main" ]]; then
    cd "${0%/*}"

    cd polynote-frontend; npm install; npm run dist

    cd ..

    sbt dist
fi
