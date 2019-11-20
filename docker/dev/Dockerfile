FROM openjdk:8-slim-buster

WORKDIR /opt

RUN apt update -y && \
    apt install -y wget python3 python3-dev python3-pip build-essential && \
    pip3 install jep jedi virtualenv

RUN wget -q https://www-us.apache.org/dist/spark/spark-2.4.4/spark-2.4.4-bin-hadoop2.7.tgz && \
    tar xfz spark-2.4.4-bin-hadoop2.7.tgz && \
    rm spark-2.4.4-bin-hadoop2.7.tgz

ENV SPARK_HOME="/opt/spark-2.4.4-bin-hadoop2.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

# First, create the distribution with `sbt dist`
# Then build this from the dist target directory (e.g., `target/scala-2.11`) using `-f ../../docker/dev/Dockerfile` to select this file.
# for example (don't forget the dot at the end!):
#   sbt dist
#   cd target/scala-2.11/
#   docker build -t polynote:dev -f ../../docker/dev/Dockerfile .
COPY polynote-dist.tar.gz .
RUN tar xfzp polynote-dist.tar.gz && \
    rm polynote-dist.tar.gz

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
