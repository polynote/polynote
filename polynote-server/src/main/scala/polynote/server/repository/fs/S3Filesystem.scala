package polynote.server.repository.fs

import fs2.Chunk
import org.jets3t.service.S3ServiceException
import org.jets3t.service.impl.rest.httpclient.RestS3Service
import org.jets3t.service.model.{S3Bucket, S3Object}
import org.jets3t.service.security.AWSCredentials
import polynote.kernel.BaseEnv
import polynote.server.repository.fs.LocalFilesystem.FileChannelWALWriter
import zio.blocking.{Blocking, effectBlocking}
import zio.interop.catz._
import zio.{RIO, Task, ZIO, system}

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file._
import scala.jdk.CollectionConverters.asScalaIteratorConverter

/**
  * You should use s3 buckets created after September 2020 using virtual-hosted path style
  * see https://aws.amazon.com/it/blogs/aws/amazon-s3-path-deprecation-plan-the-rest-of-the-story/
  */
class S3Filesystem(service: RestS3Service) extends NotebookFilesystem {

  override def readPathAsString(path: Path): RIO[BaseEnv, String] = for {
    bucket <- getBucket(path)
    obj <- effectBlocking(service.getObject(bucket, getKeyFromPath(path)))
    is = obj.getDataInputStream
    content <- readBytes(is).ensuring(ZIO.effectTotal(is.close()))
  } yield new String(content.toArray, StandardCharsets.UTF_8)

  /**
    * Writes the content to the given path "atomically" – at least, in a way where the existing file at that path (if
    * present) won't be unrecoverable if writing fails for some reason.
    */
  override def writeStringToPath(path: Path, content: String): RIO[BaseEnv, Unit] = for {
    bucket <- getBucket(path)
    key: String = getKeyFromPath(path)
    objToPut = new S3Object(bucket, key, content)
    _ <- effectBlocking(service.putObject(bucket, objToPut)).uninterruptible
  } yield ()


  // TODO still need to reimplement
  override def createLog(path: Path): RIO[BaseEnv, WAL.WALWriter] = {
    effectBlocking(path.getParent.toFile.mkdirs()) *> FileChannelWALWriter(path)
  }


  override def list(path: Path): RIO[BaseEnv, List[Path]] = {
    for {
      bucket <- getBucket(path)
      ret <- effectBlocking(service.listObjects(bucket, getKeyFromPath(path), "/"))
    } yield ret.toList.map(objectToPath)
  }

  override def validate(path: Path): RIO[BaseEnv, Unit] = {
    ZIO.unit
    //    if (path.iterator().asScala.length > maxDepth) {
    //      ZIO.fail(new IllegalArgumentException(s"Input path ($path) too deep, maxDepth is $maxDepth"))
    //    } else ZIO.unit
  }

  override def exists(path: Path): RIO[BaseEnv, Boolean] = {
    for {
      bucket <- getBucket(path)
      _ <- effectBlocking(service.getObjectDetails(bucket, getKeyFromPath(path)))
    } yield true
  }.catchSome {
    case e: S3ServiceException =>
      if (e.getResponseCode == 404) {
        ZIO.succeed(false)
      } else {
        ZIO.fail(e)
      }
  }

  override def move(from: Path, to: Path): RIO[BaseEnv, Unit] = {
    for {
      fromBucket <- getBucket(from)
      toBucket <- getBucket(to)
      _ <- effectBlocking(service.moveObject(
        fromBucket.getName,
        getKeyFromPath(from),
        toBucket.getName,
        new S3Object(getKeyFromPath(to)),
        true))
    } yield ()
  }

  override def copy(from: Path, to: Path): RIO[BaseEnv, Unit] = {
    for {
      fromBucket <- getBucket(from)
      toBucket <- getBucket(to)
      _ <- effectBlocking(
        service.copyObject(
          fromBucket.getName,
          getKeyFromPath(from),
          toBucket.getName,
          new S3Object(getKeyFromPath(to)),
          true
        )
      )
    } yield ()
  }

  override def delete(path: Path): RIO[BaseEnv, Unit] = {
    for {
      bucket <- getBucket(path)
      _ <- effectBlocking(service.getObjectDetails(bucket, getKeyFromPath(path)))
      _ <- effectBlocking(service.deleteObject(bucket, getKeyFromPath(path)))
    } yield ()
  }

  private def readBytes(is: => InputStream): RIO[BaseEnv, Chunk.Bytes] = {
    for {
      env <- ZIO.environment[BaseEnv]
      ec = env.get[Blocking.Service].blockingExecutor.asEC
      chunks <- fs2.io.readInputStream[Task](effectBlocking(is).provide(env), 8192, ec, closeAfterUse = true).compile.toChunk.map(_.toBytes)
    } yield chunks
  }

  override def init(path: Path): RIO[BaseEnv, Unit] = ZIO.unit

  def getBucket(p: Path): RIO[BaseEnv, S3Bucket] = {
    val bucket = getBucketFromPath(p)
    effectBlocking(service.getBucket(bucket)).flatMap { nullableS3bucket =>
      if (nullableS3bucket != null) ZIO.succeed(nullableS3bucket)
      else ZIO.fail(new RuntimeException(s"S3 bucket $bucket does not exists"))
    }
  }

  def getBucketFromPath(p: Path): String = {
    p.iterator().next().toString
  }

  def getKeyFromPath(p: Path): String = {
    p.iterator().asScala.drop(1).mkString("/")
  }

  def objectToPath(o: S3Object): Path = {
    Paths.get(o.getBucketName, o.getKey)
  }

}

object S3Filesystem {

  def apply(): ZIO[BaseEnv, Throwable, S3Filesystem] = {
    for {
      client <- buildS3Client
    } yield new S3Filesystem(client)
  }

  private val ACCESS_KEY_ID_PROP = "aws.accessKeyId"
  private val ACCESS_KEY_ID_ENV = "AWS_ACCESS_KEY_ID"

  private val SECRET_ACCESS_KEY_PROP = "aws.secretAccessKey"
  private val SECRET_ACCESS_KEY_ENV = "AWS_SECRET_ACCESS_KEY"

  class AccessKeyId(val value: String) extends AnyVal

  class AccessKeySecret(val value: String) extends AnyVal

  def buildS3Client: RIO[BaseEnv, RestS3Service] = {
    for {
      credentials <- resolveAmazonCredentialsTheAmazonWay()
      client <- RIO(new RestS3Service(credentials))
    } yield client
  }

  def resolveAmazonCredentialsTheAmazonWay(): RIO[BaseEnv, AWSCredentials] = {
    for {
      accessKey <- resolveOne(ACCESS_KEY_ID_PROP, ACCESS_KEY_ID_ENV, new AccessKeyId(_))
      secret <- resolveOne(SECRET_ACCESS_KEY_PROP, SECRET_ACCESS_KEY_ENV, new AccessKeySecret(_))
    } yield new AWSCredentials(accessKey.value, secret.value)
  }

  def resolveOne[A](propName: String,
                    envName: String,
                    builder: String => A): ZIO[system.System, RuntimeException, A] = {
    zio.system.properties.flatMap { map =>
      ZIO.fromOption(map.get(propName))
        .mapError(_ => new RuntimeException(s"Cannot find $propName as system property"))
    }.catchAll { _ =>
      zio.system
        .env(envName)
        .flatMap(o => ZIO.fromOption(o).mapError(_ => new RuntimeException(s"Cannot find $envName as environment variable")))
    }.map(builder)
  }
}

