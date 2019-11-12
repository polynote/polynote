FROM openjdk:8-slim-buster

ARG POLYNOTE_VERSION
ARG SCALA_VERSION="2.11"
ARG DIST_TAR="polynote-dist.tar.gz"

WORKDIR /opt

RUN apt update -y && \
    apt install -y wget python3 python3-dev python3-pip build-essential && \
    pip3 install jep jedi virtualenv

RUN if test "${SCALA_VERSION}" = "2.12"; then export DIST_TAR="polynote-dist-2.12.tar.gz"; fi && \
    wget -q https://github.com/polynote/polynote/releases/download/$POLYNOTE_VERSION/$DIST_TAR && \
    tar xfzp $DIST_TAR && \
    echo "DIST_TAR=$DIST_TAR" && \
    rm $DIST_TAR

# to wrap up, we create (safe)user
ENV UID 1000
ENV NB_USER polly

RUN adduser --disabled-password \
    --gecos "Default user" \
    --uid ${UID} \
    ${NB_USER}

# allow user access to the WORKDIR
RUN chown -R ${NB_USER}:${NB_USER} /opt/

# start image as (safe)user
USER ${NB_USER}

# expose the (internal) port that polynote runs on
EXPOSE 8192

# start polynote on container run
ENTRYPOINT ["./polynote/polynote.py"]
