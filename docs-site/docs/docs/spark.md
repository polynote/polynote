# Using Spark with Polynote

Polynote has deep integration with [Apache Spark](https://spark.apache.org), and supports running both Scala and Python
code with Spark out of the box. 

In order to use Spark, you must first have it [installed](installation.md#spark-support). Then, restart Polynote and 
open the [configuration](notebook-configuration.md) for the notebook you'd like to use with Spark, and scroll down to the 
`Spark configuration` section. As long as you set anything there - any Spark property, any [Spark template](server-configuration.md#spark)
- Polynote will launch your notebook with Spark enabled. 

![spark-master-config](images/spark-master-config.png)

Please note that if a selected Spark template sets `spark_submit_args` in either the version configuration or as part
of the template itself and you *also* specify the `sparkSubmitArgs` property in the notebook configuration, 
these will be **concatenated** as they are passed to `spark-submit`. 
They will be passed in order of least to most specific, 
with the [base-level Spark arguments](https://github.com/polynote/polynote/blob/master/config-template.yml#L149) passed first and the notebook-level arguments passed last. 
This is different than any other Spark properties (e.g. `spark.executor.memory`) specified more than 
once in different places; for those, the value that is set at the most specific configuration level 
simply takes precedence and **replaces** any other value. 