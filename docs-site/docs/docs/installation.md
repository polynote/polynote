# Installing Polynote

!!!danger
     Polynote allows **arbitrary remote code execution**, which is necessary for a notebook tool to function.
     While we'll try to improve safety by adding security measures, it will never be completely safe to
     run Polynote on your personal computer. For example:
     
     - It's possible that other websites you visit could use Polynote as an attack vector. Browsing the web
       while running Polynote is unsafe.
     - It's possible that remote attackers could use Polynote as an attack vector. Running Polynote on a
       computer that's accessible from the internet is unsafe.
     - Even running Polynote inside a container doesn't guarantee safety, as there will always be
       privilege escalation and container escape vulnerabilities which an attacker could leverage.
     
     Please be diligent about checking for new releases, as they could contain fixes for critical security
     flaws.
     
     Please be mindful of the security issues that Polynote causes; consult your company's security team
     before running Polynote. You are solely responsible for any breach, loss, or damage caused by running
     this software insecurely.


Polynote is currently only tested on Linux and MacOS, using the Chrome browser as a client. 

While we'd love it if Polynote would work perfectly on other platforms and browsers, our small team doesn't have the 
bandwidth to test every possible combination. 

Feel free to try it on your platform, and be sure to let us know about any issues you encounter by filing a bug report.

!!! help "What about Firefox?"
    While we don't test with Firefox, we are very interested in making sure that Firefox is a viable alternative to 
    Chrome when using Polynote. 

    Right now, we're in need of Firefox users who can provide us with bug reports (and, ideally, bug _fixes_ too!), so 
    please do let us know if you run into issues using Firefox!

!!! help "What about Windows?"
    Some users have reported that Polynote runs successfully on Windows Subsystem for Linux. We have no plans to support
    Windows outside of the WSL. 
    
    Please see these issues for more information: [#555](https://github.com/polynote/polynote/issues/555), [#671](https://github.com/polynote/polynote/issues/671). 

!!! tip "Using Polynote with Docker"
    Instead of installing Polynote on your computer, you can run it within a Docker container. This is a good way to get
    up and running quickly, though properly configuring the networking settings so a Docker container running on your 
    laptop can access a remote Spark cluster can be a bit tricky.

    For more information about using Docker to run Polynote, see the [Polynote Docker docs](docker.md).

## Prerequisites
  
Polynote has been successfully tested on both Java 8 and Java 11.

### Spark support 

In order to use [Apache Spark&trade;](https://spark.apache.org/) with Polynote, you'll need to have it set up in your 
environment.

!!! tip "Using Spark with Polynote"
    If you'll be using Spark with Polynote, please make sure you read this [note about Spark and Polynote](basic-usage.md#using-spark-with-polynote) for more information.
    
    Currently, Polynote supports both **Spark 2.1** (with Scala 2.11) and **2.4** (with Scala 2.11 and 2.12).

    _Some users have had success running Spark 3.0 with Scala 2.12. Please see [this issue](https://github.com/polynote/polynote/issues/926) for more information_

Polynote will use the `spark-submit` command in order to start Spark kernels. 

The `spark-submit` command must be working properly and available on the `PATH` of the environment you used to launch the 
server in order for Polynote to work properly with Spark.

??? info "Installing Spark in your environment"

    These instructions are provided as a convenience, but please note that versions and environments differ. Please
    refer to the official documentation or other online resources if you run into trouble setting up Spark.

    As mentioned above, Polynote just needs `spark-submit` to be working correctly and available on the `PATH` in the 
    environment in which it is running. It doesn't matter how you install Spark as long as those two things are true. 

    === "Standard Spark installation"
        The "standard" way to install Spark is just to extract the distribution somewhere and then set up some environment variables.
        This method works for on both Mac and Linux. 

        You may need to modify these instructions based on your local setup. 

        === "Spark on Mac"
            Here is an example setup for Polynote on Mac. Also, check out the "Installation with Homebrew" tab above for an 
            alternative installation method. 

            Install Java (this installs Java 11)

                brew cask install adoptopenjdk11

            [Download spark](https://spark.apache.org/downloads.html) and then untar it wherever you like 

                tar -zxvf spark-2.4.7-bin-hadoop2.7.tgz

            Set up the `SPARK_HOME` and `PATH` environment variables. You probably want to put these instructions in your 
            shell configuration somewhere, i.e., `~/.bashrc` or equivalent. 

            Set `SPARK_HOME` 

                export SPARK_HOME=`pwd`/spark-2.4.7-bin-hadoop2.7/

            Set `PATH`

                export PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

            Check if Spark is correctly set up by running `spark-submit`, you should see usage information. 

        === "Spark on Linux"
            Here is an example setup for Polynote on Debian. If you are not using Debian or Ubuntu, you may need to 
            modify these instructions for your distribution.

            As always, start with updating your package listing. 

                apt-get update 

            Install Java (this installs Java 11 for me on Debian buster)

                apt-get install default-jdk

            [Download spark](https://spark.apache.org/downloads.html) and then untar it wherever you like 

                tar -zxvf spark-2.4.4-bin-hadoop2.7.tgz

            Set up the `JAVA_HOME`, `SPARK_HOME` and `PATH` environment variables. You probably want to put these instructions 
            in your shell configuration somewhere, i.e., `~/.bashrc` or equivalent. 

            Set `JAVA_HOME` 

                export JAVA_HOME=/usr/lib/jvm/default-java/

            Set `SPARK_HOME` 

                export SPARK_HOME=`pwd`/spark-2.4.7-bin-hadoop2.7/

            Set `PATH`

                export PATH="$PATH:$SPARK_HOME/bin:$SPARK_HOME/sbin"

            Check if Spark is correctly set up by running `spark-submit`, you should see usage information. 

    === "Installing Spark on Mac with Homebrew"
        The [Homebrew](https://brew.sh) package manager for Mac has a [formula for Spark](https://formulae.brew.sh/formula/apache-spark), 
        but unfortunately it only installs the latest version which may not be compatible with Polynote.

        Installing an older version of Spark using Homebrew can be a bit of a pain. There is a third party repository that
        has older Spark versions available for easy download. 

        !!! warning
            It is _highly_ recommended that you read through the homebrew formula before installing it - especially when using
            a third-party repository. The [entire thing](https://github.com/eddies/homebrew-spark-tap/blob/master/Formula/apache-spark%402.4.6.rb) 
            is less than 30 lines of Ruby code and quite readable. 

            You can view the available formulae here: https://github.com/eddies/homebrew-spark-tap/tree/master/Formula

        Here's an example for Spark 2.4:

        ```shell
        brew tab eddies/spark-tap
        brew install apache-spark@2.4.6
        ```


### Python support

In order to run Polynote, you'll need to have a working Python 3 environment (including `pip`) installed. Refer to
[Python's installation instructions](https://wiki.python.org/moin/BeginnersGuide/Download){:target="_blank"} for
instructions.

!!! tip "Using Python with Polynote"
    Polynote officially supports **Python 3.7**. This is the version of Python supported by the Spark versions we're 
    targeting. 

    _Users have reported getting it to work with Python 3.6 and Python 3.8 (without Spark) with minimal effort._
  
You'll also need to install Polynote's Python dependencies:

```
pip install -r ./requirements.txt
``` 

## Download
Polynote consists of a JVM-based server application, which serves a web-based client. To try it locally, find the latest
release on the [releases page](https://github.com/polynote/polynote/releases){:target="_blank"} and download the attached
`polynote-dist.tar.gz` file (you'll find it under `Assets`). Unpack the archive:

```shell
tar -zxvpf polynote-dist.tar.gz
cd polynote
```
  
## Configure
You won't need to change any of the default configuration in order to get Polynote up and running locally. 

Please see the [configuration](server-configuration.md) documentation for more details on configuring Polynote.

## Run
To start the server, run the included python script:

```
./polynote.py
```

Once the server has started, navigate your browser to `http://localhost:8192` (if using the default network configuration).
