# Required docker version greater than 17
ARG PYTHON_VERSION=3.8.7
ARG PYTHON_IMAGE=python:${PYTHON_VERSION}-slim 
ARG JDK_IMAGE_TAG=11-slim
FROM ${PYTHON_IMAGE} as python-base

FROM openjdk:${JDK_IMAGE_TAG} as build

COPY --from=python-base / /

ARG POLYNOTE_VERSION=0.3.12
ARG SCALA_VERSION="2.12"
ARG DIST_TAR="polynote-dist.tar.gz"

WORKDIR /opt

RUN apt update -y && \
    apt install --yes --no-install-recommends wget build-essential
    
RUN if test "${SCALA_VERSION}" = "2.12"; then export DIST_TAR="polynote-dist-2.12.tar.gz"; fi && \
    wget -q https://github.com/polynote/polynote/releases/download/$POLYNOTE_VERSION/$DIST_TAR && \
    tar xfzp $DIST_TAR && \
    echo "DIST_TAR=$DIST_TAR" && \
    rm $DIST_TAR

RUN pip3 install -r ./polynote/requirements.txt

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