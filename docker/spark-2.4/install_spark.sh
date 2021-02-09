#!/bin/bash
# This script is intended for use from the docker builds.

set -e -x

HADOOP_BASE_VERSION="3.2"
HADOOP_VERSION="3.2.2"
SPARK_HADOOP_VERSION="hadoop${HADOOP_BASE_VERSION}"
SPARK_VERSION="3.0.1"
SPARK_VERSION_DIR="spark-${SPARK_VERSION}"
HADOOP_VERSION_DIR="hadoop-${HADOOP_VERSION}"

if test "${SCALA_VERSION}" = "2.12"
then
  echo "Setting SPARK_NAME and Install Hadoop"
  SPARK_NAME="spark-${SPARK_VERSION}-bin-without-hadoop-scala-2.12"
  # install hadoop as well
  pushd /opt
  wget https://archive.apache.org/dist/hadoop/common/${HADOOP_VERSION_DIR}/${HADOOP_VERSION_DIR}.tar.gz \
    && tar zxpf ${HADOOP_VERSION_DIR}.tar.gz \
    && rm ${HADOOP_VERSION_DIR}.tar.gz
  export HADOOP_HOME="/opt/${HADOOP_VERSION_DIR}"
  SPARK_DIST_CLASSPATH=$($HADOOP_HOME/bin/hadoop classpath)
  popd
else
  echo "Setting SPARK_NAME"
  SPARK_NAME="spark-${SPARK_VERSION}-bin-${SPARK_HADOOP_VERSION}"
fi

pushd /opt
wget -q "https://archive.apache.org/dist/spark/${SPARK_VERSION_DIR}/${SPARK_NAME}.tgz" \
  && tar zxpf "${SPARK_NAME}.tgz" \
  && mv "${SPARK_NAME}" spark \
  && rm "${SPARK_NAME}.tgz"
popd

if test -z "${SPARK_DIST_CLASSPATH}"
then
  echo "Skipping spark env"
else
  echo "export SPARK_DIST_CLASSPATH=\"${SPARK_DIST_CLASSPATH}\"" > /opt/spark/conf/spark-env.sh
fi
