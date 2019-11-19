ARG POLYNOTE_VERSION
ARG SCALA_VERSION="2.11"
FROM polynote/polynote:${POLYNOTE_VERSION}-${SCALA_VERSION}

WORKDIR /opt

USER root

RUN wget -q https://www-us.apache.org/dist/spark/spark-2.4.4/spark-2.4.4-bin-hadoop2.7.tgz && \
    tar xfz spark-2.4.4-bin-hadoop2.7.tgz && \
    rm spark-2.4.4-bin-hadoop2.7.tgz

ENV SPARK_HOME="/opt/spark-2.4.4-bin-hadoop2.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

# switch to non-root user
USER ${NB_USER}
