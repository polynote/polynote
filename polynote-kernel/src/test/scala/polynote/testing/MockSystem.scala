package polynote.testing
import zio.{IO, UIO, ULayer, ZIO, ZLayer}
import zio.system.System.{Service => SystemService}

final case class MockSystem(
  envMap: Map[String, String],
  propertyMap: Map[String, String],
  lineSeparatorStr: String
) extends SystemService {
  override def env(variable: String): IO[SecurityException, Option[String]] =
    ZIO.succeed(envMap.get(variable))

  override def envOrElse(variable: String, alt: => String): IO[SecurityException, String] =
    ZIO.succeed(envMap.getOrElse(variable, alt))

  override def envOrOption(variable: String, alt: => Option[String]): IO[SecurityException, Option[String]] =
    ZIO.succeed(envMap.get(variable) orElse alt)

  override def envs: IO[SecurityException, Map[String, String]] =
    ZIO.succeed(envMap)

  override def lineSeparator: UIO[String] =
    ZIO.succeed(lineSeparatorStr)

  override def properties: IO[Throwable, Map[String, String]] =
    ZIO.succeed(propertyMap)

  override def property(prop: String): IO[Throwable, Option[String]] =
    ZIO.succeed(propertyMap.get(prop))

  override def propertyOrElse(prop: String, alt: => String): IO[Throwable, String] =
    ZIO.succeed(propertyMap.getOrElse(prop, alt))

  override def propertyOrOption(prop: String, alt: => Option[String]): IO[Throwable, Option[String]] =
    ZIO.succeed(propertyMap.get(prop) orElse alt)
}

object MockSystem {
  def apply(envMap: Map[String, String], propertyMap: Map[String, String]): MockSystem =
    new MockSystem(envMap, propertyMap, java.lang.System.lineSeparator())

  def ofEnvs(envs: (String, String)*): MockSystem = apply(envs.toMap, Map.empty)
  def ofProps(props: (String, String)*): MockSystem = apply(Map.empty, props.toMap)

  def layerOfEnvs(envs: (String, String)*): ULayer[zio.system.System] = ZLayer.succeed(ofEnvs(envs: _*))
  def layerOfProps(props: (String, String)*): ULayer[zio.system.System] = ZLayer.succeed(ofProps(props: _*))
}