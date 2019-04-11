#!/usr/bin/env bash
set -x
set -e

DIR=$(dirname "$0")

source ${DIR}/install-jep.sh || true  # it's ok if this script isn't present

mkdir ${DIR}/notebooks || true # a directory named notebooks is required.

polynote_jar=${DIR}/polynote-spark-assembly.jar

# We don't normally add scala-library to our classpath because spark-submit handles it. However, in this case we want it.
# So, we can extract it from the polynote assembly jar because we snuck it in there earlier ;)
scala_jar="scala-library.jar"
jar xf ${polynote_jar} ${scala_jar}

actual_command=$(java -cp ${scala_jar}:${polynote_jar} polynote.server.SparkServer --printCommand 2>&1 | sed -n "s/^.*SparkSubmit: \(.*\).*$/\1/p")
echo "${actual_command}"
${actual_command}