package polynote.runtime

class ScalaCell extends AnyRef with Serializable {

  // this prevents typecheck errors, but if cells do get serialized/deserialized in the same JVM could lead to weirdness.
  // TODO: figure out the cause of the type error
  protected def readResolve(): Object = this

}
