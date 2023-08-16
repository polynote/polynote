package polynote.kernel.dependency

import java.io.File

/**
  * Represents a downloaded JVM artifact
  */
case class Artifact(
  isRootDependency: Boolean,
  url: String,
  file: File,
  source: Option[File]
)
