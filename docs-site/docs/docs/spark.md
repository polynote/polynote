# Using Spark with Polynote

Polynote has deep integration with [Apache Spark](https://spark.apache.org), and supports running both Scala and Python
code with Spark out of the box. 

In order to use Spark, you must first have it [installed](installation.md#spark-support). Then, open the 
[configuration](notebook-configuration.md) for the notebook you'd like to use with Spark, and scroll down to the 
`Spark configuration` section. As long as you set anything there - any Spark property, any [Spark template](server-configuration.md#spark)
- and Polynote will launch your notebook with Spark enabled. 

![spark-master-config](images/spark-master-config.png)