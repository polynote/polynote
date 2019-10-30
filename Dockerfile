FROM openjdk:8-slim-buster

ENV POLYNOTE_VERSION="0.2.9"
ENV SPARK_HOME="/polynote/spark-2.4.4-bin-hadoop2.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

WORKDIR /polynote

RUN apt update -y && \
    apt install -y wget python3 python3-dev python3-pip build-essential && \
    pip3 install jep jedi pyspark virtualenv

RUN wget -q https://github.com/polynote/polynote/releases/download/$POLYNOTE_VERSION/polynote-dist.tar.gz && \
    tar xfzp polynote-dist.tar.gz && \
    rm polynote-dist.tar.gz && \
    sed -e "20,22 s/^#//" -e "s/127.0.0.1/0.0.0.0/" polynote/config-template.yml | tee polynote/config.yml
RUN wget -q https://www-us.apache.org/dist/spark/spark-2.4.4/spark-2.4.4-bin-hadoop2.7.tgz && \
    tar xfz spark-2.4.4-bin-hadoop2.7.tgz && \
    rm spark-2.4.4-bin-hadoop2.7.tgz

EXPOSE 8192

CMD ["./polynote/polynote.py"]