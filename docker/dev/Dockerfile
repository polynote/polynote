FROM openjdk:8-slim-buster

ARG SCALA_VERSION="2.11"

WORKDIR /opt

RUN apt update -y && \
    apt install -y wget python3 python3-dev python3-pip build-essential

COPY docker/spark-2.4/install_spark.sh .
RUN ./install_spark.sh
ENV SPARK_HOME="/opt/spark"
ENV HADOOP_HOME="/opt/hadoop-2.7.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

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
