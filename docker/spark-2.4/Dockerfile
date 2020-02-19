ARG POLYNOTE_VERSION
ARG SCALA_VERSION="2.11"
FROM polynote/polynote:${POLYNOTE_VERSION}-${SCALA_VERSION}

WORKDIR /opt

USER root

COPY scripts/install_spark.sh .
ADD https://archive.apache.org/dist/hadoop/common/hadoop-2.7.7/hadoop-2.7.7.tar.gz /opt
RUN ./install_spark.sh
RUN rm /opt/hadoop-2.7.7.tar.gz
ENV SPARK_HOME="/opt/spark"
ENV HADOOP_HOME="/opt/hadoop-2.7.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

# switch to non-root user
USER ${NB_USER}
