# Example notebook

This is an example of a polynote notebook. The notebook file itself is just markdown. Code fences in the markdown file
become code cells in the notebook. The text in between is treated as "text cells", using
[markdown-it](https://github.com/markdown-it/markdown-it). Why do it this way?

* Notebooks are standard markdown documents, so they render great in source control.
* All notebooks (i.e. Jupyter, Zeppelin et. al.) use markdown for their text cells anyway, so it makes sense.

A con is that you can't really put code fences *into your text*, because they'd be regarded as code cells. A solution to that is to just let
it be a code cell, but disable run.

Anyway, here's a code cell for example purposes. This one is Scala.

```scala
import scala.collections.JavaConverters._

case class Foo(id: String, foo: Double) {
  def blarg[T](foo: T): Blarg[T] = foo match {
    case Blorg() => s"""
         whuhh? $foo is the $id
       """
    case Blarg() => "what"
    case whatever => 'foo
    case nope => ???
    case _ => throw new Exception(s"abc${de}fg")
  }
}
```

Soon, we'll be able to have the output of the code cell also save into the markdown file.

You can also have python code cells:

```python
from sklearn import *

def foo(a, b, c):
  a.blarg(b);
  return c;
```

And some more text content.
