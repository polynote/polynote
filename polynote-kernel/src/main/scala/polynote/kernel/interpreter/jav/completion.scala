package polynote.kernel.interpreter.jav

import java.io.File
import java.lang.reflect.{GenericArrayType, Method, Modifier, ParameterizedType, TypeVariable, WildcardType}

import polynote.kernel.interpreter.jav.eval.Evaluator
import polynote.kernel.interpreter.jav.expressions.{Import, Type}
import polynote.kernel.interpreter.jav.reflection.{ClassReflection, MemberReflection, MethodReflection}


package object completion {

  case class CompletionCandidate(value: String, forms: Seq[String])

  object CompletionCandidate {
    def apply(value: String): CompletionCandidate = CompletionCandidate(value, Seq(value))
  }

  ////

  case class CompletionResult(expression: String, position: Int, candidates: Seq[CompletionCandidate])

  ////

  trait Completer {
    def complete(expression: String): CompletionResult
  }

  ////

  case class AggregateCompleter(completers: Seq[Completer]) extends Completer {
    override def complete(expression: String): CompletionResult = {
      println("COMPLETE: " + expression)
      val group = completeGroup(expression)
      group match {
        case None => CompletionResult(expression, 0, Seq())
        case Some((position, candidates)) => CompletionResult(expression, position, candidates.flatMap(_.candidates))
      }
    }

    private def completeGroup(expression: String): Option[(Int, Seq[CompletionResult])] = {
      completers.map(_.complete(expression))
        .distinct
        .groupBy(_.position)
        .toList
        .sortBy(_._1)
        .reverse
        .headOption
    }
  }

  ////

  case class ResolvedClass(resolvedPackage: ResolvedPackage, className: String) {
    def canonicalClassName: String = (if(resolvedPackage.packageName.isEmpty) "" else resolvedPackage.packageName + ".") + className
  }

  case class ResolvedPackage(packageName: String, file: File) {
    val classResolver: () => Seq[ResolvedClass] = () => TypeResolver.classes(this)
    def classes: Seq[ResolvedClass] = classResolver()
  }

  ////

  class TypeResolver(val packageResolver: () => Seq[ResolvedPackage]) {
    def packages: Seq[ResolvedPackage] = packageResolver()
  }

  object TypeResolver {
    def defaultPackageResolver: TypeResolver = new TypeResolver(() => {
      packagesFromFiles(classpathJars ++ runtimeJars)
    })

    private def packagesFromFiles(files: Seq[File]): Seq[ResolvedPackage] = {
      files.flatMap(packagesFromFile).distinct.sortBy(_.packageName)
    }

    private def packagesFromFile(file: File): Seq[ResolvedPackage] = {
      util.entries(file)
        .filter(s => s.endsWith(".class") && !s.contains("$"))
        .map(s => s.replaceFirst("[a-zA-Z0-9_]*.class$", "").replaceFirst("/$", "").replace("/", "."))
        .distinct
        .map(pn => ResolvedPackage(pn, file))
    }

    private def classpathJars: Seq[File] = {
      System.getProperty("java.class.path").split(File.pathSeparator).map(s => new File(s))
    }

    private def runtimeJars: Seq[File] = {
      import scala.collection.JavaConverters._
      ClassLoader.getSystemClassLoader.getResources("java/lang/Object.class").asScala.toSeq
        .map(u => u.toString.split("!/").head.split(":").last)
        .distinct
        .map(s => new File(s))
    }

    def classes(resolvedPackage: ResolvedPackage): Seq[ResolvedClass] = {
      util.entries(resolvedPackage.file)
        .map(_.replace("/", "."))
        .filter(p => p.startsWith(resolvedPackage.packageName) && p.endsWith(".class") && !p.contains("$"))
        .map(_.replaceFirst("\\.class$", ""))
        .filter(!_.replace(resolvedPackage.packageName + ".", "").contains("."))
        .distinct
        .map(s => ResolvedClass(resolvedPackage, s.substring(s.lastIndexOf('.') + 1)))
    }
  }

  ////

  case class ConsoleCompleter(evaluator: Evaluator, typeResolver: TypeResolver) extends Completer {
    override def complete(expression: String): CompletionResult = {
      val lastSpace = expression.lastIndexOf(" ") + 1
      val candidates = (results ++ methods ++ types ++ imports)
        .filter(_.startsWith(expression.substring(lastSpace)))
        .map(CompletionCandidate(_))

      CompletionResult(expression, lastSpace, candidates)
    }

    private def results: Seq[String] = evaluator.results.map(_.key)
    private def methods: Seq[String] = evaluator.expressions.flatMap { e =>
      e match {
        case m: expressions.Method => Seq(m)
        case _ => Seq()
      }
    }.map(_.name).distinct.map(s => s"${s}(")

    private def types: Seq[String] = evaluator.expressions.flatMap { e =>
      e match {
        case t: Type => Seq(t)
        case _ => Seq()
      }
    }.map(_.key)

    private def imports: Seq[String] = {
      val importedPackages = evaluator.expressions.flatMap { e =>
        e match {
          case i: Import => Seq(i)
          case _ => Seq()
        }
      }.map { i =>
        i.typePackage.replace(".*", "").replace(" ", "")
      }.toSet

      typeResolver.packages
        .filter(rp => importedPackages.contains(rp.packageName))
        .flatMap(_.classes)
        .map(_.className)
        .distinct
    }
  }

  private def candidateName(member: MemberReflection[_]): String = member match {
    case method: MethodReflection => method.name + "("
    case _ => member.name
  }

  private def renderSimpleGenericType(typ: java.lang.reflect.Type): String = typ match {
    case pt: ParameterizedType => renderSimpleGenericType(pt.getRawType) +
      "<" + pt.getActualTypeArguments.map(renderSimpleGenericType).mkString(", ") + ">"
    case tv: TypeVariable[_] => tv.getName
    case gat: GenericArrayType => renderSimpleGenericType(gat.getGenericComponentType) + "[]"
    case c: Class[_] => c.getSimpleName
    case wt: WildcardType => wt.toString
  }

  private def genericMethodSignature(method: Method): String = {
    renderSimpleGenericType(method.getGenericReturnType) + " " +
      method.getName + "(" + method.getGenericParameterTypes.map(renderSimpleGenericType).mkString(", ") + ")" +
      {
        val etypes = method.getGenericExceptionTypes
        (if (etypes.nonEmpty) " throws " else "") + etypes.map(renderSimpleGenericType).mkString(", ")
      }
  }

  private def candidateForm(member: MemberReflection[_]): String = member match {
    case method: MethodReflection => genericMethodSignature(method.member)
    case cr: ClassReflection => cr.member.getSimpleName
    case _ => member.name
  }

  ////

  case class InstanceMemberCompleter(evaluator: Evaluator) extends Completer {
    override def complete(expression: String): CompletionResult = {
      val lastSeparator = expression.lastIndexOf(" ") + 1
      val packagePart = expression.substring(lastSeparator)
      val canComplete = packagePart.matches("[a-zA-Z0-9\\$_\\\\.\\(\\[\\]]*") && packagePart.contains(".")

      val beginIndex = packagePart.lastIndexOf('.') + 1
      val aClassOption = if(canComplete) {
        evaluator.typeOfExpression(packagePart.substring(0, beginIndex - 1)).map(t => t.asInstanceOf[Class[_]])
      } else {
        None
      }

      aClassOption match {
        case Some(aClass) => {
          val classReflection = ClassReflection(aClass)
          val join = (
            classReflection.declaredFields ++
            classReflection.declaredMethods)
            .distinct

          val candidates = join
            .filter(m => Modifier.isPublic(m.modifiers) && !Modifier.isStatic(m.modifiers))
            .groupBy(candidateName)
            .map {
              case (k, r) => CompletionCandidate(k, r.map(candidateForm).sorted)
            }
            .filter(c => c.value.startsWith(packagePart.substring(beginIndex)))
            .toSeq

          CompletionResult(expression, lastSeparator + beginIndex, candidates)
        }
        case None => CompletionResult(expression, 0, Seq())
      }
    }
  }

  ////

  case class StaticMemberCompleter(evaluator: Evaluator) extends Completer {
    override def complete(expression: String): CompletionResult = {
      val lastSeparator = expression.lastIndexOf(" ") + 1
      val packagePart = expression.substring(lastSeparator)

      val completion = completionFor(packagePart)
      completion match {
        case Some((klass, v)) => {
          val candidates = ClassReflection(klass)
            .declaredMembers
            .filter(m => Modifier.isStatic(m.modifiers) && Modifier.isPublic(m.modifiers) && !m.isSynthetic)
            .groupBy(candidateName)
            .map {
              case (k, r) => CompletionCandidate(k, r.map(candidateForm).sorted)
            }
            .filter(c => c.value.startsWith(v))
            .toSeq

          val beginIndex = packagePart.lastIndexOf('.') + 1
          CompletionResult(expression, lastSeparator + beginIndex, candidates)
        }
        case None => CompletionResult(expression, 0, Seq())
      }
    }


    def completionFor(expression: String): Option[(Class[_], String)] = {
      val parsedClass = parseExpression((expression, Seq()))
      parsedClass.filter {
        case (_, second) => second.nonEmpty
      } map {
        case(first, second) => (evaluator.classFrom(first).get, second.mkString(".").trim)
      }
    }

    def parseExpression(expression: (String, Seq[String])): Option[(String, Seq[String])] = {
      val expressionClassOption = evaluator.classFrom(expression._1)

      expressionClassOption match {
        case Some(x) => Some(expression)
        case None => if(expression._1.contains(".")) {
          val packagePart = expression._1.substring(0, expression._1.lastIndexOf("."))
          val classPart = expression._1.substring(expression._1.lastIndexOf(".") + 1)

          parseExpression((packagePart, List(classPart) ++ expression._2))
        } else {
          None
        }
      }
    }
  }

  ////

  case class StringCompleter(candidates: Seq[String]) extends Completer {
    override def complete(expression: String): CompletionResult = {
      val lastSpace = expression.lastIndexOf(" ") + 1
      CompletionResult(
        expression,
        lastSpace,
        candidates.filter(c => c.startsWith(expression.substring(lastSpace))).map(CompletionCandidate(_)))
    }
  }

  ////

  case class TypeCompleter(typeResolver: TypeResolver) extends Completer {
    override def complete(expression: String): CompletionResult = {
      val lastSpace = expression.lastIndexOf(" ") + 1
      val packagePart = expression.substring(lastSpace)
      val beginIndex = packagePart.lastIndexOf('.') + 1

      val resolvedPackages = typeResolver.packages.filter(p => p.packageName.startsWith(packagePart))

      val classesInPackage = if(beginIndex > 0) {
        typeResolver.packages
          .filter(p => p.packageName == packagePart.substring(0, beginIndex - 1))
          .flatMap(_.classes)
          .map(_.canonicalClassName)
          .filter(_.startsWith(packagePart))
      } else {
        Seq()
      }

      val candidates = (resolvedPackages.map(_.packageName) ++ classesInPackage)
        .map(candidatePackagePrefix(beginIndex))
        .filter(!_.isEmpty)
        .distinct
        .sorted
        .map(CompletionCandidate(_))
        .toSeq

      CompletionResult(expression, lastSpace + beginIndex, candidates)
    }

    private def candidatePackagePrefix(fromIndex: Int)(item: String): String = {
      var toIndex = item.indexOf('.', fromIndex)
      if(toIndex < fromIndex) {
        toIndex = item.length
      }
      item.substring(fromIndex, toIndex)
    }
  }

  ////

  val javaKeywordCompleter: Completer = StringCompleter(Seq(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "continue", "default", "double", "do", "else", "enum",
    "extends", "false", "final", "finally", "float", "for", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new", "null",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "true", "try", "void", "volatile", "while"
  ).sorted)

  ////

  def defaultCompleter(evaluator: Evaluator, typeResolver: TypeResolver): Completer = {
    AggregateCompleter(
      Seq(
        javaKeywordCompleter,
        ConsoleCompleter(evaluator, typeResolver),
        TypeCompleter(typeResolver),
        // TypeCompleter
        StaticMemberCompleter(evaluator),
        InstanceMemberCompleter(evaluator)
      )
    )
  }
}
