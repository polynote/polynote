#!/bin/bash
# This script is intended for use from the docker builds.

SCALA_VERSION=${1:-$SCALA_VERSION}
SPARK_VERSION=${2:-$SPARK_VERSION}
HADOOP_VERSION=${3:-$HADOOP_VERSION}
HADOOP_VERSION_MAJOR=${4:-$HADOOP_VERSION_MAJOR}

set -e -x

if [ -z ${SCALA_VERSION+x} ]; then echo "SCALA_VERSION not set"; exit 1; fi
if [ -z ${SPARK_VERSION+x} ]; then echo "SPARK_VERSION not set"; exit 1; fi
if [ -z ${HADOOP_VERSION+x} ]; then echo "HADOOP_VERSION not set"; exit 1; fi
if [ -z ${HADOOP_VERSION_MAJOR+x} ]; then echo "HADOOP_VERSION_MAJOR not set"; exit 1; fi

SPARK_VERSION_DIR="spark-${SPARK_VERSION}"

echo $SCALA_VERSION
echo $SPARK_VERSION
echo $HADOOP_VERSION
echo $HADOOP_VERSION_MAJOR

if test "${SCALA_VERSION}" = "2.12"
then
  if test "${SPARK_VERSION:0:1}" = "3"
  then
     SPARK_NAME="spark-${SPARK_VERSION}-bin-without-hadoop"
  else
     SPARK_NAME="spark-${SPARK_VERSION}-bin-without-hadoop-scala-${SCALA_VERSION}"
  fi
  # install hadoop as well
  pushd /opt
  wget https://archive.apache.org/dist/hadoop/common/hadoop-${HADOOP_VERSION}/hadoop-${HADOOP_VERSION}.tar.gz \
    && tar zxpf hadoop-${HADOOP_VERSION}.tar.gz \
    && rm hadoop-${HADOOP_VERSION}.tar.gz
  export HADOOP_HOME="/opt/hadoop-${HADOOP_VERSION}"
  SPARK_DIST_CLASSPATH=$($HADOOP_HOME/bin/hadoop classpath)
  popd
else
  SPARK_NAME="spark-${SPARK_VERSION}-bin-hadoop${HADOOP_VERSION_MAJOR}"
fi

pushd /opt
wget "https://downloads.apache.org/spark/${SPARK_VERSION_DIR}/${SPARK_NAME}.tgz" \
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
