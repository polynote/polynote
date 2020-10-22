/*
    The following file will create the table for the polynote application.
*/

-- this is required for the UUID functions within postgres
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

DROP TABLE if exists polynote_storage;

CREATE TABLE polynote_storage (
  relative_folder_path TEXT NOT NULL,
  notebook_name TEXT NOT NULL,
  notebook_contents TEXT NULL,
  notebook_uuid uuid DEFAULT uuid_generate_v4(),
  created_date TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_date TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  notebook_description TEXT NULL,
  notebook_version INTEGER NOT NULL DEFAULT 1
);


INSERT INTO polynote_storage (notebook_name, notebook_contents, relative_folder_path) VALUES
('et ultrices posuere', '{ "metadata" : { "config" : { "dependencies" : { }, "exclusions" : [ ], "repositories" : [ ], "env" : { } }, "language_info" : { "name" : "scala" } }, "nbformat" : 4, "nbformat_minor" : 0, "cells" : [ { "cell_type" : "markdown", "execution_count" : 0, "metadata" : { " language" : "text" }, "language" : "text", "source" : [ "# Test2\n", "\n", "This is a text cell. Start editing!" ], "outputs" : [ ] }, { "cell_type" : "code", "execution_count" : 1, "metadata" : { " cell.metadata.exec_info" : { "startTs" : 1587667272508, "endTs" : 1587667272849 }, "language" : "scala" }, "language" : "scala", "source" : [ "println( \"hello world\")\n" ], "outputs" : [ { "name" : "stdout", "text" : [ "hello world\n" ], "output_type" : "stream" } ] } ]}','/folder/sub-1'),
('non velit donec', '{ "metadata" : { "config" : { "dependencies" : { }, "exclusions" : [ ], "repositories" : [ ], "env" : { } }, "language_info" : { "name" : "scala" } }, "nbformat" : 4, "nbformat_minor" : 0, "cells" : [ { "cell_type" : "markdown", "execution_count" : 0, "metadata" : { " language" : "text" }, "language" : "text", "source" : [ "# Test2\n", "\n", "This is a text cell. Start editing!" ], "outputs" : [ ] }, { "cell_type" : "code", "execution_count" : 1, "metadata" : { " cell.metadata.exec_info" : { "startTs" : 1587667272508, "endTs" : 1587667272849 }, "language" : "scala" }, "language" : "scala", "source" : [ "println( \"hello world\")\n" ], "outputs" : [ { "name" : "stdout", "text" : [ "hello world\n" ], "output_type" : "stream" } ] } ]}','/folder/sub-1'),
('tristique est', '{ "metadata" : { "config" : { "dependencies" : { }, "exclusions" : [ ], "repositories" : [ ], "env" : { } }, "language_info" : { "name" : "scala" } }, "nbformat" : 4, "nbformat_minor" : 0, "cells" : [ { "cell_type" : "markdown", "execution_count" : 0, "metadata" : { " language" : "text" }, "language" : "text", "source" : [ "# Test2\n", "\n", "This is a text cell. Start editing!" ], "outputs" : [ ] }, { "cell_type" : "code", "execution_count" : 1, "metadata" : { " cell.metadata.exec_info" : { "startTs" : 1587667272508, "endTs" : 1587667272849 }, "language" : "scala" }, "language" : "scala", "source" : [ "println( \"hello world\")\n" ], "outputs" : [ { "name" : "stdout", "text" : [ "hello world\n" ], "output_type" : "stream" } ] } ]}','/folder/sub-1'),

-- These items are copied from the Polynote examples and saved in a /Polynote folder path
('Hello World Example','{"metadata":{"config":{"dependencies":{},"exclusions":[],"repositories":[],"sparkConfig":{}}},"nbformat":4,"nbformat_minor":0,"cells":[{"cell_type":"markdown","execution_count":0,"metadata":{"language":"text"},"language":"text","source":["# Hello World\n","\n","\n","Woohoo, my first Polynote notebook!\n","\n","\n"],"outputs":[]},{"cell_type":"code","execution_count":1,"metadata":{"cell.metadata.exec_info":{"startTs":1572052463366,"endTs":1572052463973},"language":"scala"},"language":"scala","source":["val hi = \"I can write something in Scala\""],"outputs":[]},{"cell_type":"code","execution_count":2,"metadata":{"cell.metadata.exec_info":{"startTs":1572052464011,"endTs":1572052466313},"language":"python"},"language":"python","source":["print(hi, \"and print it in Python!\")"],"outputs":[{"name":"stdout","text":["I can write something in Scala and print it in Python!\n"],"output_type":"stream"}]},{"cell_type":"code","execution_count":3,"metadata":{"cell.metadata.exec_info":{"startTs":1572052466315,"endTs":1572052466620},"language":"python"},"language":"python","source":[],"outputs":[]}]}' ,'/Polynote'),
('Accessing a Python object in Scala', '{"metadata":{"config":{"dependencies":{},"exclusions":[],"repositories":[],"sparkConfig":{}}},"nbformat":4,"nbformat_minor":0,"cells":[{"cell_type":"markdown","execution_count":0,"metadata":{"language":"text"},"language":"text","source":["# Accessing a Python object in Scala\n","\n","\n","You can call methods and properties of Python objects in Scala code. Consider the following Python class:\n","\n","\n"],"outputs":[]},{"cell_type":"code","execution_count":1,"metadata":{"cell.metadata.exec_info":{"startTs":1572053589496,"endTs":1572053591961},"language":"python"},"language":"python","source":["class MyPythonClass:\n","    \"\"\"A simple example class\"\"\"\n","    i = 12345\n","\n","    def f(self):\n","        return ''hello world''\n","    \n","    def add(self, a, b):\n","        return a + b\n","    \n","    def getLength(self, thing):\n","        return len(thing)\n","\n","pyInst = MyPythonClass()"],"outputs":[]},{"cell_type":"markdown","execution_count":2,"metadata":{"language":"text"},"language":"text","source":["Simple actions work in Scala:"],"outputs":[]},{"cell_type":"code","execution_count":3,"metadata":{"cell.metadata.exec_info":{"startTs":1572053621295,"endTs":1572053621661},"language":"scala"},"language":"scala","source":["pyInst.f() // note that this must be called with `()` like in Python. "],"outputs":[{"execution_count":3,"data":{"text/plain":["hello world"]},"metadata":{"name":"Out","type":"PythonObject"},"output_type":"execute_result"}]},{"cell_type":"code","execution_count":4,"metadata":{"cell.metadata.exec_info":{"startTs":1572053645552,"endTs":1572053645802},"language":"scala"},"language":"scala","source":["pyInst.i"],"outputs":[{"execution_count":4,"data":{"text/plain":["12345"]},"metadata":{"name":"Out","type":"Out"},"output_type":"execute_result"}]},{"cell_type":"code","execution_count":5,"metadata":{"cell.metadata.exec_info":{"startTs":1572053654502,"endTs":1572053654879},"language":"scala"},"language":"scala","source":["pyInst.add(1, Math.PI)"],"outputs":[{"execution_count":5,"data":{"text/plain":["4.141592653589793"]},"metadata":{"name":"Out","type":"PythonObject"},"output_type":"execute_result"}]},{"cell_type":"code","execution_count":6,"metadata":{"cell.metadata.exec_info":{"startTs":1572053660806,"endTs":1572053661095},"language":"scala"},"language":"scala","source":["pyInst.getLength(Array(1, 2, 3))"],"outputs":[{"execution_count":6,"data":{"text/plain":["3"]},"metadata":{"name":"Out","type":"PythonObject"},"output_type":"execute_result"}]},{"cell_type":"markdown","execution_count":7,"metadata":{"language":"text"},"language":"text","source":["Note that passing Scala values into Python only works for primitive values right now. So, for example, the follow cell will fail because Python doesn''t know what to do with a Scala `Seq`. "],"outputs":[]},{"cell_type":"code","execution_count":8,"metadata":{"cell.metadata.exec_info":{"startTs":1572053741115,"endTs":1572053741333},"language":"scala"},"language":"scala","source":["pyInst.getLength(Seq(1,2,3))"],"outputs":[{"ename":"zio.FiberFailure","evalue":"Fiber failed.\nA checked error was not handled.\njep.JepException: <class ''TypeError''>: object of type ''jep.PyJObject'' has no len()\n\tat <ast>.getLength(<ast>:12)\n\tat jep.python.PyCallable.call(Native Method)\n\tat jep.python.PyCallable.callAs(PyCallable.java:110)\n\tat polynote.runtime.python.PythonObject$$anonfun$1.apply(PythonObject.scala:23)\n\tat polynote.runtime.python.PythonObject$$anonfun$1.apply(PythonObject.scala:22)\n\tat zio.blocking.Blocking$Service$$anonfun$effectBlocking$1$$anonfun$apply$3.apply(Blocking.scala:133)\n\tat zio.blocking.Blocking$Service$$anonfun$effectBlocking$1$$anonfun$apply$3.apply(Blocking.scala:127)\n\tat zio.internal.FiberContext.evaluateNow(FiberContext.scala:333)\n\tat zio.internal.FiberContext.zio$internal$FiberContext$$run$body$2(FiberContext.scala:602)\n\tat zio.internal.FiberContext$$anonfun$7.run(FiberContext.scala:602)\n\tat java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)\n\tat java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)\n\tat java.lang.Thread.run(Thread.java:748)\n\nFiber:35501 was supposed to continue to:\n  a future continuation at zio.blocking.Blocking$Service$$anonfun$effectBlocking$1$$anonfun$apply$4$$anonfun$apply$5.apply(Blocking.scala:145)\n  a future continuation at zio.ZIO$$anonfun$ensuring$1$$anonfun$apply$13.apply(ZIO.scala:317)\n  a future continuation at zio.blocking.Blocking$Service$$anonfun$effectBlocking$1$$anonfun$apply$7.apply(Blocking.scala:126)\n  a future continuation at zio.ZIO$$anonfun$run$2.apply(ZIO.scala:1120)\n  a future continuation at zio.ZIO$$anonfun$bracket_$1.apply(ZIO.scala:144)\n  a future continuation at zio.ZIO$$anonfun$run$2.apply(ZIO.scala:1120)\n  a future continuation at zio.ZIO$$anonfun$bracket_$1.apply(ZIO.scala:144)\n  a future continuation at polynote.kernel.interpreter.python.PythonInterpreter$$anon$3$$anonfun$run$1.apply(PythonInterpreter.scala:46)\n\nFiber:35501 execution trace:\n  at zio.ZIO$$anonfun$flatten$1.apply(ZIO.scala:402)\n  at zio.ZIO$ZipLeftFn$$anonfun$apply$172.apply(ZIO.scala:2665)\n  at zio.UIO$$anonfun$effectSuspendTotal$1.apply(UIO.scala:183)\n  at zio.Fiber$$anonfun$join$2.apply(Fiber.scala:69)\n  at zio.Fiber$$anonfun$join$1.apply(Fiber.scala:69)\n  at zio.internal.FiberContext$$anonfun$await$1.apply(FiberContext.scala:618)\n  at zio.blocking.Blocking$Service$$anonfun$effectBlocking$1$$anonfun$apply$4.apply(Blocking.scala:127)\n  at zio.ZIOFunctions$$anonfun$effectSuspendTotal$1.apply(ZIO.scala:1935)\n  at polynote.runtime.python.PythonObject$$anonfun$1.apply(PythonObject.scala:22)\n  at zio.blocking.package$$anonfun$effectBlocking$1.apply(blocking.scala:34)\n  at zio.ZIO$$anonfun$bracket_$2.apply(ZIO.scala:144)\n  at zio.internal.FiberContext$$anonfun$lock$2.apply(FiberContext.scala:543)\n  at zio.internal.FiberContext$$anonfun$lock$1.apply(FiberContext.scala:543)\n  at zio.ZIO$$anonfun$bracket_$2.apply(ZIO.scala:144)\n  at zio.internal.FiberContext$$anonfun$1.apply(FiberContext.scala:471)\n\nFiber:35501 was spawned by: <empty trace>","traceback":[],"output_type":"error"}]},{"cell_type":"code","execution_count":9,"metadata":{"language":"scala"},"language":"scala","source":[],"outputs":[]}]}', '/Polynote'),
('Accessing a Scala object in Python', '{"metadata":{"config":{"dependencies":{},"exclusions":[],"repositories":[],"sparkConfig":{}}},"nbformat":4,"nbformat_minor":0,"cells":[{"cell_type":"markdown","execution_count":0,"metadata":{"language":"text"},"language":"text","source":["# Accessing a Scala object in Python\n","\n","\n","You can call methods and properties of Scala objects in Python code. Consider the following Scala class:\n","\n","\n"],"outputs":[]},{"cell_type":"code","execution_count":1,"metadata":{"cell.metadata.exec_info":{"startTs":1572054439197,"endTs":1572054441590},"language":"scala"},"language":"scala","source":["class MySimpleClass(val i: Int = 12345) {\n","    def f = \"hello world\"\n","    def add(a: Float, b: Float) = a + b\n","    def getLength[T](thing: java.util.ArrayList[T]) = thing.size()\n","}\n","val scInst = new MySimpleClass()"],"outputs":[]},{"cell_type":"code","execution_count":2,"metadata":{"cell.metadata.exec_info":{"startTs":1572054441599,"endTs":1572054442693},"language":"python"},"language":"python","source":["scInst.f()"],"outputs":[{"execution_count":2,"data":{"text/plain":["hello world"]},"metadata":{"name":"Out","type":"String"},"output_type":"execute_result"}]},{"cell_type":"code","execution_count":3,"metadata":{"cell.metadata.exec_info":{"startTs":1572054442695,"endTs":1572054442814},"language":"python"},"language":"python","source":["scInst.i() # Note that the `()` are necesary"],"outputs":[{"execution_count":3,"data":{"text/plain":["12345"]},"metadata":{"name":"Out","type":"Long"},"output_type":"execute_result"}]},{"cell_type":"code","execution_count":4,"metadata":{"cell.metadata.exec_info":{"startTs":1572054442815,"endTs":1572054442940},"language":"python"},"language":"python","source":["from math import pi\n","scInst.add(1.0, pi)"],"outputs":[{"execution_count":4,"data":{"text/plain":["4.141592979431152"]},"metadata":{"name":"Out","type":"Double"},"output_type":"execute_result"}]},{"cell_type":"code","execution_count":5,"metadata":{"cell.metadata.exec_info":{"startTs":1572054442942,"endTs":1572054443073},"language":"python"},"language":"python","source":["scInst.getLength([1, 2, 3, 4])"],"outputs":[{"execution_count":5,"data":{"text/plain":["4"]},"metadata":{"name":"Out","type":"Long"},"output_type":"execute_result"}]}]}', '/Polynote'),
('Case class access in Python', '{"metadata":{"config":{"dependencies":{},"exclusions":[],"repositories":[],"sparkConfig":{}}},"nbformat":4,"nbformat_minor":0,"cells":[{"cell_type":"markdown","execution_count":0,"metadata":{"language":"text"},"language":"text","source":["# Case class access in Python\n","\n","\n","Just a little demonstration of accessing Scala case class members in Python. \n","\n","First, we need a  case class:\n","\n","\n"],"outputs":[]},{"cell_type":"code","execution_count":1,"metadata":{"cell.metadata.exec_info":{"startTs":1572052580836,"endTs":1572052583111},"language":"scala"},"language":"scala","source":["case class Foo(someParam: Int, otherParam: String)\n","val foo = new Foo(1, \"hi\")"],"outputs":[]},{"cell_type":"markdown","execution_count":3,"metadata":{"language":"text"},"language":"text","source":["Recall that Scala case class members are actually getter functions. In Python, we''ll need to treat them as such"],"outputs":[]},{"cell_type":"code","execution_count":2,"metadata":{"cell.metadata.exec_info":{"startTs":1572052676317,"endTs":1572052676485},"language":"python"},"language":"python","source":["someParamInPython = foo.someParam()\n","print(\"someParamInPython is:\", someParamInPython)\n","otherParamInPython = foo.otherParam()\n","print(\"otherParamInPython is:\", otherParamInPython)"],"outputs":[{"name":"stdout","text":["someParamInPython is:"," ","1","\n","otherParamInPython is:"," ","hi","\n"],"output_type":"stream"}]},{"cell_type":"markdown","execution_count":4,"metadata":{"language":"text"},"language":"text","source":["That''s it!"],"outputs":[]}]}', '/Polynote')
;
