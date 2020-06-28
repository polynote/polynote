ARG POLYNOTE_VERSION
ARG SCALA_VERSION="2.11"
FROM polynote/polynote:${POLYNOTE_VERSION}-${SCALA_VERSION}

WORKDIR /opt

USER root

COPY install_spark.sh .
RUN ./install_spark.sh && rm ./install_spark.sh
ENV SPARK_HOME="/opt/spark"
ENV HADOOP_HOME="/opt/hadoop-2.7.7"
ENV PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

# switch to non-root user
USER ${NB_USER}
