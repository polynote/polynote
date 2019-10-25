---
title: Installing Polynote
layout: docs
---

# Installing Polynote


> ## Warning!
> Polynote is a web-based programming notebook tool. Like other notebook tools, a large part of its usefulness relies on
> **arbitrary remote code execution.**
>
> It currently contains **no built-in security or authentication** of its own, and relies entirely on the user deploying
> and configuring it in a secure way.
> 
> Polynote should only be deployed on a secure server which has its own security and authentication mechanisms which
> prevent all unauthorized network access.
>
> You are solely responsible for any damage or other loss that occurs as a result of running Polynote.
{:.warning} 

## Download
Polynote consists of a JVM-based server application, which serves a web-based client. To try it locally, find the latest
release on the [releases page](https://github.com/polynote/polynote/releases){:target="_blank"} and download the attached
`polynote-dist.tar.gz` file (you'll find it under `Assets`). Unpack the archive:

```
tar -zxvpf polynote-dist.tar.gz
cd polynote
```

## Prerequisites
- Polynote is currently only tested on Linux and MacOS, using the Chrome browser as a client. We hope to be testing
  other platforms and browsers soon. Feel free to try it on your platform, and be sure to let us know about any issues
  you encounter by filing a bug report.
  
- *Spark support*: In order to use Spark with kernel isolation, you'll need to [install Apache Spark&trade;](https://spark.apache.org/downloads.html).
  If you'll be using Spark with Polynote, please make sure you read this [note about Spark and Polynote](02-basic-usage.md#Using-Spark-with-Polynote) for more information. 
  - Polynote will use the `spark-submit` command in order to start isolated kernels, so it needs the `spark-submit` command 
    to be working properly and available on the `PATH` of the environment you used to launch the server.
      - On a Mac with [Homebrew](https://brew.sh), you can install Spark locally with `brew install apache-spark`.
      - On Linux, untar Spark wherever you like and configure your environment to have `SPARK_HOME` pointing to the Spark location. 
        Then add `$SPARK_HOME/bin` to your `PATH`. You'll also need Java 8 and `JAVA_HOME` properly configured. 
        Here is an example setup for Polynote on Debian. Note that distributions differ, so you may need to modify these 
        instructions for your local setup.
        - Make sure Java 8 is installed: `apt-get install openjdk-8-jdk`
        - Download and untar polynote somewhere (e.g., `/home/user/polynote`)
        - Download and untar spark somewhere (e.g., `/home/user/spark`)
        - Set `JAVA_HOME`: `export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64/`
        - Set `SPARK_HOME` to the untarred spark dir (e.g., `export SPARK_HOME=/home/user/spark/`)
        - Set `PATH`: `export PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"`
        - Check if Spark is correctly set up by running `spark-submit`: you should see usage information. 
        - Do the Python setup as described below. You might need to install `build-essential` if jep installation fails. 
        - Run Polynote!
      
- *Python support*: In order to execute Python code, you'll need to have Python 3 and pip3 installed. Refer to
  [Python's installation instructions](https://wiki.python.org/moin/BeginnersGuide/Download){:target="_blank"} for
  instructions on installing these packages.
  
  You'll also need to install some Python dependencies `jep`, `jedi`, `virtualenv`:
  
  ```
  pip3 install jep jedi pyspark virtualenv
  ``` 
  
  For PySpark support you'll want to install `pyspark` as well. 
  
  Additionally, you will probably want to install `numpy` and `pandas`. 
  

## Configure
To change any of the default configuration, you'll need to copy the included `config-template.yaml` file to `config.yaml`.

## Run
To start the server, run the included bash script:

```
./polynote
```

## Next
Once the server has started, navigate your browser to `http://localhost:8192` (if using the default network configuration).
Next, read about [basic usage](02-basic-usage.md)
