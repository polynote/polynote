package polynote.kernel.interpreter.jav

import java.net.URLClassLoader
import java.io.{File, FileNotFoundException, StringWriter}
import java.lang.reflect.{Type => JType}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import javax.tools.{DiagnosticCollector, JavaFileObject, ToolProvider}
import polynote.kernel.interpreter.jav.expressions.{Assignment, AssignmentWithType, Expression, Import, Method, Statement, Value, Type}

package object eval {
  case class EvaluationResult(key: String, value: Object, typ: Option[JType]) {

    def resolveType: JType = typ.getOrElse(if(value != null) value.getClass else classOf[Object])

    override def toString: String = String.format("%s %s = %s", rendering.renderType(resolveType), key, rendering.renderValue(value))
  }

  ////

  case class Evaluation(expression: Expression, result: Option[EvaluationResult]) {
    override def toString: String = expression + "=>" + result
  }

  ////

  case class EvaluationContext(outputDirectory: File,
                               expressions: Seq[Expression],
                               rawResults: Seq[EvaluationResult],
                               lastSource: Option[String]) extends EvaluationLookup {
    val results: Seq[EvaluationResult] = rawResults.reverse.groupBy(_.key).map(_._2.head).toSeq

    def result(key: String): Option[EvaluationResult] = results.find(_.key == key)

    def nextResultKey: String = "res" + results.size

    def lastSource(lastSource: String): EvaluationContext = copy(lastSource = Some(lastSource))

    def addResult(newResult: EvaluationResult): EvaluationContext = copy(rawResults = rawResults :+ newResult)
    def addResults(newResults: Seq[EvaluationResult]): EvaluationContext = copy(rawResults = rawResults ++ newResults)
    def setResults(newResults: Seq[EvaluationResult]): EvaluationContext = copy(rawResults = newResults)
    def addExpression(newExpression: Expression): EvaluationContext = copy(expressions = expressions :+ newExpression)
    def removeExpressionWithKey(key: String): EvaluationContext = copy(expressions = expressions.filterNot(_.key == key))
    def setExpressions(newExpressions: Seq[Expression]): EvaluationContext = copy(expressions = newExpressions)

    override def valueOf[T](key: String): T = {
      val result = results.find(key == _.key)
      if(result.isEmpty) {
        throw new IllegalArgumentException(s"Result '${key}' not found'")
      }
      result.get.value.asInstanceOf[T]
    }
  }

  object EvaluationContext {
    def apply(): EvaluationContext = EvaluationContext(null)
    def apply(outputDirectory: File): EvaluationContext = EvaluationContext(
      if(outputDirectory != null) outputDirectory else Files.createTempDirectory("JavaREPL").toFile,
      defaultExpressions,
      List(),
      None
    )

    private def defaultJavaImports: Seq[Expression] =
      List(
        Import("import java.lang.*", "java.lang.*"),
        Import("import java.util.*", "java.util.*"),
        Import("import java.math.*", "java.math.*"),
        Import("import static java.lang.Math.*", "java.lang.Math.*"))

    private def defaultJava8Imports: Seq[Expression] =
      if(util.javaVersionAtLeast("1.8.0")) {
        List(Import("import java.util.function.*", "java.util.function.*"))
      } else {
        List()
      }

    private def defaultExpressions: Seq[Expression] = defaultJavaImports ++ defaultJava8Imports
  }

  ////

  class EvaluationClassLoader(context: EvaluationContext, parent: ClassLoader) extends URLClassLoader(Array(context.outputDirectory.toURL), parent) {

  }

  ////

  class Evaluator(var context: EvaluationContext, val classLoader: ClassLoader) {

    def evaluate(expr: String): Either[Throwable, Evaluation] = {
      parseExpression(expr).right.flatMap { expression =>
        val rfv = evaluate(expression)
        if(rfv.isLeft && rfv.left.get.isInstanceOf[ExpressionCompilationException] && expression.isInstanceOf[Value]) {
          val rfs = evaluate(Statement(expr))
          if(rfs.isLeft && rfs.left.get.isInstanceOf[ExpressionCompilationException]) {
            Left(new ExpressionCompilationException(List(rfs.left.get.getMessage, rfv.left.get.getMessage).distinct.mkString("\n\n")))
          } else {
            rfs
          }
        } else {
          rfv
        }
      }
    }

    def parseExpression(expression: String): Either[Throwable, Expression] = {
      if(Import.matches(expression)) {
        createImport(expression)
      } else if(Type.matches(expression)) {
        createTypeExpression(expression)
      } else if(Method.matches(expression)) {
        createMethodExpression(expression)
      } else if(AssignmentWithType.matches(expression)) {
        createAssignmentWithType(expression)
      } else if(Assignment.matches(expression)) {
        createAssignmentExpression(expression)
      } else {
        createValueExpression(expression)
      }
    }

    def setResults(results: Seq[EvaluationResult]): Unit = {
      context = context.setResults(results)
    }

    def setExpressions(expressions: Seq[Expression]): Unit = {
      context = context.setExpressions(expressions)
    }

    private def createImport(expression: String): Either[Throwable, Expression] = {
      Right(Import.extract(expression).get)
    }

    private def createAssignmentWithType(expression: String): Either[Throwable, Expression] = {
      try {
        val matcher = AssignmentWithType.pattern.matcher(expression)
        val m = matcher.find()
        val declaredMethod = detectMethod(matcher.group(1) + " " + util.randomIdentifier("method") + "(){}")
        Right(AssignmentWithType(expression, declaredMethod.getGenericReturnType, matcher.group(2), matcher.group(3)))
      } catch {
        case e: Exception => Left(util.unwrapException(e))
      }
    }

    private def createAssignmentExpression(expression: String): Either[Throwable, Expression] = {
      Right(Assignment.extract(expression).get)
    }

    private def createTypeExpression(expression: String): Either[Throwable, Expression] = {
      Right(Type.extract(expression).get)
    }

    private def createValueExpression(expression: String): Either[Throwable, Expression] = {
      Right(Value(expression))
    }

    def lastSource: Option[String] = context.lastSource
    def results: Seq[EvaluationResult] = context.results
    def result(key: String): Option[EvaluationResult] = context.result(key)

    def expressions: Seq[Expression] = context.expressions
    def outputDirectory: File = context.outputDirectory

    def tryEvaluate(expression: String): Either[Throwable, Evaluation] = {
      val localEvaluator = new Evaluator(context, new EvaluationClassLoader(context, classLoader.getParent))
      localEvaluator.evaluate(expression)
    }

    def typeOfExpression(expression: String): Option[JType] = {
      val evaluation = tryEvaluate(expression)
      evaluation match {
        case Left(e) => None
        case Right(r) => r.result.map(_.resolveType)
      }
    }

    def classFrom(expression: String): Option[Class[_]] = {
      try {
        Some(detectClass(expression))
      } catch {
        case e: Throwable => None
      }
    }

    def clearOutputDirectory(): Unit = {
      //deleteFiles(context.outputDirectory());
      //delete(context.outputDirectory());
      ???
    }

    private def evaluate(expression: Expression): Either[Throwable, Evaluation] = {
      expression match {
        case typ: Type => evaluate(typ)
        case _ => evaluateExpression(expression)
      }
    }

    private def evaluate(expression: Type): Either[Throwable, Evaluation] = {
      if(ToolProvider.getSystemJavaCompiler == null) {
        Left(new FileNotFoundException("Java compiler not found." +
          "This can occur when JavaREPL was run with JRE instead of JDK or JDK is not configured correctly."))
      } else {
        try {
          val outputPath = util.directory(context.outputDirectory, expression.typePackage.getOrElse("").replace('.', File.separatorChar))
          val outputJavaFile = util.file(outputPath, expression.typ + ".java")

          val sources = rendering.renderExpressionClass(context, expression.typ, expression)
            .replace(rendering.EXPRESSION_TOKEN, rendering.renderExpressionSource(expression))

          Files.write(outputJavaFile.toPath, sources.getBytes(StandardCharsets.UTF_8))
          compile(outputJavaFile)

          classLoader.loadClass(expression.canonicalName)
          context = context.addExpression(expression).lastSource(sources)

          Right(Evaluation(expression, None))
        } catch {
          case e: Exception => Left(util.unwrapException(e))
        }
      }
    }

    private def createMethodExpression(expression: String): Either[Throwable, Expression] = {
      try {
        val declaredMethod = detectMethod(expression)
        Right(
          Method(
            expression,
            declaredMethod.getGenericReturnType,
            declaredMethod.getName,
            declaredMethod.getParameterTypes.toSeq))
      } catch {
        case e: Exception => Left(util.unwrapException(e))
      }
    }

    private def detectMethod(expression: String): java.lang.reflect.Method = {
      val className = util.randomIdentifier("Method")
      val outputJavaFile = util.file(context.outputDirectory, className + ".java")

      val sources = rendering.renderMethodSignatureDetection(context, className, expression)
      Files.write(outputJavaFile.toPath, sources.getBytes(StandardCharsets.UTF_8))
      compile(outputJavaFile)
      val expressionClass = classLoader.loadClass(className)
      expressionClass.getDeclaredMethods()(0)
    }

    private def detectClass(expression: String): Class[_] = {
      val className = util.randomIdentifier("Class")
      val outputJavaFile = util.file(context.outputDirectory, className + ".java")

      val sources = rendering.renderMethodSignatureDetection(
        context,
        className,
        "public " + expression + " detectClass(){return null;}")

      Files.write(outputJavaFile.toPath, sources.getBytes(StandardCharsets.UTF_8))
      compile(outputJavaFile)

      val expressionClass = classLoader.loadClass(className)

      expressionClass.getDeclaredMethods()(0).getReturnType
    }

    private def evaluateExpression(expression: Expression): Either[Throwable, Evaluation] = {
      val className = util.randomIdentifier("Evaluation")

      try {
        val newContext = context.removeExpressionWithKey(expression.key)
        val outputJavaFile = util.file(context.outputDirectory, className + ".java")
        val sources = rendering.renderExpressionClass(newContext, className, expression)
          .replace(rendering.EXPRESSION_TOKEN, rendering.renderExpressionSource(expression))

        Files.write(outputJavaFile.toPath, sources.getBytes(StandardCharsets.UTF_8))
        compile(outputJavaFile)
        val expressionClass = classLoader.loadClass(className)
        val constructor = expressionClass.getDeclaredConstructor(classOf[EvaluationLookup])

        val expressionInstance = constructor.newInstance(newContext)

        val method = expressionClass.getMethod("evaluate")
        val resultObject = method.invoke(expressionInstance)

        val modifiedResults = getModifiedResults(expressionInstance.asInstanceOf[Object])

        if(resultObject != null || !method.getReturnType.equals(Void.TYPE)) {
          val result = EvaluationResult(nextResultKeyFor(expression), resultObject, typeFor(expression))
          context = newContext.addExpression(expression).addResults(modifiedResults :+ result).lastSource(sources)
          Right(Evaluation(expression, Some(result)))
        } else {
          context = newContext.addExpression(expression).addResults(modifiedResults).lastSource(sources)
          Right(Evaluation(expression, None))
        }
      } catch {
        case e: Throwable => Left(util.unwrapException(e))
      }
    }

    private def getModifiedResults(expressionInstance: Object): Seq[EvaluationResult] = {
      expressionInstance.getClass.getDeclaredFields
        .foldLeft(List[EvaluationResult]())((results, field) => {
          results ++ result(field.getName).filter(_.value != field.get(expressionInstance)).map(List(_)).getOrElse(List())
        })
    }

    private def nextResultKeyFor(expression: Expression): String = {
      expression match {
        case v: Value => context.nextResultKey
        case _ => expression.key
      }
    }
    private def typeFor(expression: Expression): Option[JType] = {
      expression match {
        case awt: AssignmentWithType => Some(awt.typ)
        case _ => None
      }
    }
    ////

    private def buildCompileClassPath: String = (
      List(outputDirectory.getAbsolutePath) ++
        util.getClassLoaderUrls(classLoader).map(url => new File(url.getFile).getPath) ++
        List(System.getProperty("java.class.path"))
      ).mkString(File.pathSeparator)

    private def compile(file: File): Unit = {
      val compiler = ToolProvider.getSystemJavaCompiler
      val diagnostics = new DiagnosticCollector[JavaFileObject]
      val fileManager = compiler.getStandardFileManager(diagnostics, null, null)

      import scala.collection.JavaConverters._
      val compilationUnits = fileManager.getJavaFileObjectsFromFiles(List(file).asJava)
      val output = new StringWriter()
      val task = compiler.getTask(
        output,
        fileManager,
        diagnostics,
        List(//"-verbose,"
          "-cp", buildCompileClassPath,
          // Have to set source output so that annotation processor generated classes are visible.
          "-s", outputDirectory.getAbsolutePath()).asJava,
        null,
        compilationUnits
      )

      try {
        val result = task.call()
        if(!result) {
          System.err.println("-----------------------------------")
          System.err.println("compile(\"" + file.getAbsolutePath + "\")")
          System.err.println("OUTPUT:" + output.toString)
          System.err.println("DIAGNOSTICS:\n" + diagnostics.getDiagnostics)
          System.err.println("-----------------------------------")
          throw new ExpressionCompilationException(file, diagnostics.getDiagnostics)
        }
      } finally {
        fileManager.close()
      }
    }
  }

}