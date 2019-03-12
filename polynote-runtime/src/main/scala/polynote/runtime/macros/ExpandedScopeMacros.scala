package polynote.runtime.macros

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.reflect.api.Universe
import scala.reflect.macros.whitebox

/**
  * This macro allows for injecting orphan instances into implicit scope, as follows.
  *
  * Given a typeclass org.foo.TC[A], and a separate module that wants to put instances of TC into TC's implicit scope,
  * place a low-priority implicit fundep materialization pointing to the macro method [[ExpandedScopeMacros#resolveFromScope]].
  *
  * Then, in the separate module, create a subclass of TC, e.g. com.bar.TC1[A]. It must have the same type parameters as TC
  * and directly extend TC with those type parameters. Place the would-be orphan instances/derivations into TC1's companion
  * object, making them instances of TC1 rather than TC.
  *
  * Then, create a resource file in the separate module called `META-INF/expanded-scopes/org.foo.TC` (the fully qualified
  * class name of the original typeclass). In this file, there should be a line with the fully qualified name of each
  * such subclass of TC, i.e. `com.bar.TC1`.
  *
  * When instances are searched for org.foo.TC[X], and no instances with higher priority are found, the fundep macro will
  * scan the classpath and find all such resource files, and for each subclass found it will attempt to resolve an implicit
  * for e.g. com.bar.TC1[X] and if successful will return that as an instance of org.foo.TC[X].
  *
  * This allows modules to be created containing orphan instances of TC, while still placing them in implicit scope. This
  * is used, for example, to allow the polynote-spark-runtime module to define instances of [[polynote.runtime.ReprsOf]]
  * that are in implicit scope for ReprsOf, without making polynote-runtime depend on spark.
  */
class ExpandedScopeMacros(val c: whitebox.Context) {
  import c.universe._
  import ExpandedScopeMacros.resourceBase

  def resolveFromScope: Tree = {
    val A = c.enclosingImplicits.head.pt
    val typeConstructor = A.typeConstructor
    val typeName = typeConstructor.typeSymbol.fullName
    val typeArgs = A.typeArgs

    def loadSubtypes = {
      val resources = Option(Thread.currentThread().getContextClassLoader).getOrElse(getClass.getClassLoader).getResources(s"$resourceBase/$typeName").asScala.toList
      resources.flatMap {
        url => scala.io.Source.fromURL(url).getLines().toList
      }.flatMap {
        subtypeName =>
          try {
            val nameTree = c.parse(subtypeName)
            val typedNameTree = c.typecheck(nameTree, c.TYPEmode)
            Some(typedNameTree.tpe.companion)
          } catch {
            case err: Throwable =>
              val e = err
              None
          }
      }
    }

    // TODO: something is weird with this cache... the second time around it doesn't find the right reprs!
    //       Fix it! (context: It didn't seem to be a problem before the plotting stuff)
    val subtypes = ExpandedScopeMacros.cache.getOrElseUpdate(
      typeName,
      loadSubtypes
    ).asInstanceOf[List[c.Type]]

    object InstanceFound {
      def unapply(typ: Type): Option[Tree] = try {
        val applied = appliedType(typ.typeConstructor, typeArgs)
        c.inferImplicitValue(applied, false) match {
          case EmptyTree => None
          case tree => Some(tree)
        }
      } catch {
        case err: Throwable =>
          val e = err
          None
      }
    }

    subtypes.collectFirst {
      case InstanceFound(tree) => tree
    }.getOrElse(c.abort(c.enclosingPosition, s"No valid expanded scopes found for $A"))
  }

}

object ExpandedScopeMacros {
  private val resourceBase: String = "META-INF/expanded-scopes"
  private val cache: mutable.HashMap[String, List[Universe#Type]] = mutable.HashMap()
}
