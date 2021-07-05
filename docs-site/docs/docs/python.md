For now, Python is the major non-JVM-based language you'll be using with Polynote. Polynote uses
[Jep](https://github.com/ninia/jep) to execute Python code within the JVM. Jep does most of the heavy-lifting when it 
comes to Python interop. 

Most Python code should work out of the box on Polynote, without needing anything special. Please let us know if you
run into any problems with Python code that works well in Jupyter or the Python REPL. 

### Python dependencies

When you specify a Python dependency in the [configuration](notebook-configuration.md#python-dependencies), Polynote 
creates a [virtual environment](https://virtualenv.pypa.io/en/latest/) scoped to your notebook. 

This virtual environment is reused on subsequent runs of the notebook, unless your dependencies change or you explicitly
[bust the cache](notebook-configuration.md#dependency-caching). 

Additionally, the virtual environments are isolated from each
other but not from the system, since Polynote specifies the 
[`--system-site-packages`](https://virtualenv.pypa.io/en/latest/cli_interface.html#system-site-packages) flag when
creating the environment. 

!!!info "Experimental: PySpark And Dependencies"
    Polynote attempts to add the dependencies you specify to Spark by downloading their zip files and adding them to the 
    Spark context if it exists. 

    This means that your Python dependencies _should_ be shipped to your executors (and available) to your code that 
    runs there! 

### Sharing between Python and Scala

Our goals right now are to support a few, key use-cases with a focus on sharing from Scala to Python,
such as plotting data generated in Scala with `matplotlib`, or using Scala-generated data with `tensorflow` and
`scikit-learn`. We've found that the interop between Python and Scala can be very powerful even if it is limited to these
simple cases.


!!!tip
    If you're going to be moving back and forth between Python and Scala a lot, we highly recommend reading 
    [how Jep works](https://github.com/ninia/jep/wiki/How-Jep-Works).

Here are a few important points to keep in mind when sharing between Python and Scala:

* Jep handles the conversion from Scala -> Python.
    * It converts primitives and strings into brand-new Python primitives and strings.
    * An object of any other type is wrapped as a `PyJObject`, which is an interface allowing Python to directly access
      that objects attributes. Note that in this case, nothing is copied - `PyJObject` holds a reference to the underlying
      JVM object.
    * Note that Jep is based on Java, not Scala. This means that when it wraps a Scala object as a `PyJObject`, you won't
      get Scala sugar - things like multiple parameter lists, implicits, etc. - when you work with it in Python.
      This can limit your ability to use a lot of super-scala-stuff with Python.
* Jep handles conversion from Python -> Scala and Polynote adds a little bit of sugar on top.
    * Similar to the other way round, Jep automatically converts primitives and strings into brand-new JVM primitives and strings.
    * Additionally, Jep supports some other conversions such as Python `dict` to `java.util.HashMap`
    * Polynote will retrieve an object of any other type as a `PyObject`. Similar to `PyJObject`, a `PyObject` wraps a pointer
      to a Python object. Polynote has some support for handling certain types of Python objects, typically for visualization
      purposes.

Note that these implementation details may change and while we'll work hard to update this information we can't guarantee
that it won't get out-of-date. Of course, feel free to [drop us a line](https://gitter.im/polynote/polynote) if you
think that's the case!