FROM openjdk:8-slim-buster

WORKDIR /opt

RUN apt update -y && \
    apt install -y wget python3 python3-dev python3-pip build-essential && \
    pip3 install jep jedi virtualenv

# First, create the distribution with `sbt dist`
# Then this from the dist target directory (e.g., `target/scala-2.11`) using `-f ../../docker/dev/Dockerfile` to select this file.
# for example (don't forget the dot at the end!):
#   docker build -t polynote:dev -f ../../docker/dev/Dockerfile .
COPY polynote-dist.tar.gz .
RUN tar xfzp polynote-dist.tar.gz && \
    rm polynote-dist.tar.gz

RUN wget -q https://www-us.apache.org/dist/spark/spark-2.4.4/spark-2.4.4-bin-hadoop2.7.tgz && \
    tar xfz spark-2.4.4-bin-hadoop2.7.tgz && \
    rm spark-2.4.4-bin-hadoop2.7.tgz

ENV SPARK_HOME="/opt/spark-2.4.4-bin-hadoop2.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

RUN pip3 install pyspark==2.4.4

EXPOSE 8192

ENTRYPOINT ["./polynote/polynote.py"]