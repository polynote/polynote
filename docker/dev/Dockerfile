# Set this to match your JDK version compiled with.
FROM openjdk:8-slim-buster

WORKDIR /opt

RUN apt update -y && \
    apt install -y wget python3 python3-dev python3-pip build-essential

COPY docker/spark/install_spark.sh .
RUN ./install_spark.sh
ENV SPARK_HOME="/opt/spark"
ENV HADOOP_HOME="/opt/hadoop-2.7.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

# Copy over the requirements early so that we can cache this layer seperate from polynote-dist
COPY requirements.txt /tmp/
RUN pip3 install -r /tmp/requirements.txt


# Before starting, double check your JVM version
# and make sure it matches the version in the FROM line.
# Once you've got that sorted out, compile the website with npm
# Package everything together by making the distribution with `sbt dist`
# Then build this from the project root using `-f docker/dev/Dockerfile` to select this file.
# for example (don't forget the dot at the end!):
#   cd polynote-static; npm install; npm run dist; cd..
#   sbt dist
#   docker build -t polynote:dev -f docker/dev/Dockerfile .
COPY target/polynote-dist.tar.gz .
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
