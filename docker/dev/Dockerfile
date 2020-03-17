FROM openjdk:8-alpine

ARG SCALA_VERSION="2.11"

# Intall spark.
ENV SPARK_HOME "/usr/lib/spark"
RUN wget https://apache.uib.no/spark/spark-2.4.5/spark-2.4.5-bin-hadoop2.7.tgz && \
    tar -zxpf spark-2.4.5-bin-hadoop2.7.tgz -C /tmp && \
    mv /tmp/spark-2.4.5-bin-hadoop2.7 ${SPARK_HOME} && \
    rm -rf /tmp/spark-2.4.5-bin-hadoop2.7 && \
    rm spark-2.4.5-bin-hadoop2.7.tgz
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

RUN echo "**** install Python ****" && \
    apk add --no-cache python3 linux-pam && \
    if [ ! -e /usr/bin/python ]; then ln -sf python3 /usr/bin/python ; fi

RUN echo "**** install pip ****" && \
    apk add --virtual build-dependencies build-base python3-dev && \
    python3 -m ensurepip && \
    rm -r /usr/lib/python*/ensurepip && \
    pip3 install --no-cache --upgrade pip setuptools wheel && \
    if [ ! -e /usr/bin/pip ]; then ln -s pip3 /usr/bin/pip ; fi

RUN echo "**** install python dependencies ****" && \
    python3 -m pip install jep jedi virtualenv

RUN echo "**** install bash ****" && \
    apk add --no-cache bash

WORKDIR /opt

# First, create the distribution with `sbt dist`
# Then build this from the project root using `-f docker/dev/Dockerfile` to select this file.
# for example (don't forget the dot at the end!):
#   sbt dist
#   docker build -t polynote:dev -f docker/dev/Dockerfile .
# or, to build for 2.12:
#   sbt +dist
#   SCALA_VERSION=2.12 docker build --build-arg SCALA_VERSION -t polynote:dev_2.12 -f docker/dev/Dockerfile .
COPY target/scala-$SCALA_VERSION/polynote-dist*.tar.gz .
RUN tar xfzp polynote-dist*.tar.gz && \
    rm polynote-dist*.tar.gz

# to wrap up, we create (safe)user
ENV UID 1000
ENV NB_USER polly
RUN adduser  -u ${UID} --shell /bin/false -D -S ${NB_USER}

# allow user access to the WORKDIR
RUN chown -R ${NB_USER} /opt/

# start image as (safe)user
USER ${NB_USER}

# expose the (internal) port that polynote runs on
EXPOSE 8192

# start polynote on container run
ENTRYPOINT ["./polynote/polynote.py"]
