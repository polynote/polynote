#!/bin/bash
# This script is intended for use from the docker builds.

set -e -x

SPARK_VERSION="2.4.5"
SPARK_VERSION_DIR="spark-${SPARK_VERSION}"

if test "${SCALA_VERSION}" = "2.12"
then
  SPARK_NAME="spark-${SPARK_VERSION}-bin-without-hadoop-scala-2.12"
  # install hadoop as well
  pushd /opt
  wget https://archive.apache.org/dist/hadoop/common/hadoop-2.7.7/hadoop-2.7.7.tar.gz \
    && tar zxpf hadoop-2.7.7.tar.gz \
    && rm hadoop-2.7.7.tar.gz
  export HADOOP_HOME="/opt/hadoop-2.7.7"
  SPARK_DIST_CLASSPATH=$($HADOOP_HOME/bin/hadoop classpath)
  popd
else
  SPARK_NAME="spark-${SPARK_VERSION}-bin-hadoop2.7"
fi

pushd /opt
wget -q "https://downloads.apache.org/spark/${SPARK_VERSION_DIR}/${SPARK_NAME}.tgz" \
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