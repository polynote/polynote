---
title: Mixing programming languages
layout: docs
---

## Mixing programming languages

One of Polynote's most interesting features is its support for polyglot notebooks, where cells within the same notebook 
can be written in different languages. For example, a notebook could do some data manipulation with Scala and Spark, 
and then plot that data in Python with Matplotlib. 

We find that this lets us do some pretty neat things, but it does have some limitations and caveats, and plenty of edge-cases
left to be worked out. 

### Execution state in Polynote

We hinted [previously](02-basic-usage.md#The-symbol-table-and-input-scope) that the way Polynote handles cell execution
is a little special.

As a reminder, the kernel keeps track of all the symbols defined by a cell execution. These symbols are part 
of the cell's state, which is made available to downstream cells (those below the cell in question) when they in turn 
are executed. 

Polynote stores these symbols, alongside their types and other information, in a Scala-based format. Symbols defined by
and provided to other languages are wrapped (and unwrapped) appropriately. 

### Sharing between Python and Scala

For now, Python is the major non-JVM-based language you'll be using with Polynote. Polynote uses 
[jep](https://github.com/ninia/jep) to do most of the heavy-lifting when it comes to Python interop. If you're going to
be moving back and forth between Python and Scala a lot, we highly recommend at least reading about 
[how Jep works](https://github.com/ninia/jep/wiki/How-Jep-Works).

Our goals right now are to support a few, key use-cases with a focus on sharing from Scala to Python, 
such as plotting data generated in Scala with `matplotlib`, or using Scala-generated data with `tensorflow` and 
`scikit-learn`. We've found that the interop between Python and Scala can be very powerful even if its limited to these 
simple cases. 

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

### Cookbook

We have a bunch of example notebooks over in the [examples folder](https://github.com/polynote/polynote/tree/master/docs/examples), 
showcasing various useful tricks and things to keep in mind while working with Polynote. 

