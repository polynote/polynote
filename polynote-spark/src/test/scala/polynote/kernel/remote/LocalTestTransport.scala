package polynote.kernel.remote
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import cats.syntax.functor._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import org.scalatest.Assertions
import org.scalatest.exceptions.TestFailedException
import polynote.config.PolynoteConfig
import polynote.messages.Notebook

import scala.collection.mutable.ListBuffer
import scala.collection.immutable.{Queue => ScalaQueue}

class LocalTestTransport(implicit contextShift: ContextShift[IO]) extends Transport[LocalTestTransportServer] {
  private val logger = org.log4s.getLogger

  val log: ListBuffer[Either[RemoteRequest, RemoteResponse]] = new ListBuffer[Either[RemoteRequest, RemoteResponse]]

  private var expectation: ScalaQueue[Either[RemoteRequest => Boolean, RemoteResponse => Boolean]] = ScalaQueue.empty
  private var runningExpectations = false
  private val complete = Deferred.unsafe[IO, Either[Throwable, Unit]]
  lazy val server: LocalTestTransportServer = new LocalTestTransportServer(this)
  lazy val client: LocalTestTransportClient = new LocalTestTransportClient(server, this)

  def expect(fn: Expectation => Expectation): Unit = {
    expectation = fn(Expectation()).expected
  }

  @volatile private var isDone: Boolean = false

  private def finish(result: Either[Throwable, Unit]): IO[Unit] =
    complete.complete(result).map(_ => isDone = true)

  def logReq(req: RemoteRequest): IO[Unit] = IO {
    logger.info(s"--> $req")
    synchronized {
      if (runningExpectations) {
        val dequeued = expectation.dequeueOption
        dequeued match {
          case None => if (!isDone) throw new Exception("Unexpected message after queue drained")
          case Some((matcher, rest)) =>
            matcher match {
              case Left(fn) => if (!fn(req)) throw new MatchError(req)
              case Right(fn) => throw new MatchError(req)
            }
            expectation = rest
            if (rest.isEmpty) {
              finish(Right(())).unsafeRunSync()
            }
        }
      }
      log += Left(req)
      ()
    }
  }.handleErrorWith(err => finish(Left(err)))

  def logRep(rep: RemoteResponse): IO[Unit] = IO {
    logger.info(s"<-- $rep")
    synchronized {
      if (runningExpectations) {
        val dequeued = expectation.dequeueOption
        dequeued match {
          case None => if(!isDone) throw new Exception("Unexpected message after queue drained")
          case Some((matcher, rest)) =>
            matcher match {
              case Right(fn) => if (!fn(rep)) throw new MatchError(rep)
              case Left(fn) => throw new MatchError(rep)
            }
            expectation = rest
            if (rest.isEmpty) {
              finish(Right(())).unsafeRunSync()
            }
        }
      }
      log += Right(rep)
      ()
    }
  }.handleErrorWith(err => finish(Left(err)))

  def serve(config: PolynoteConfig, notebook: Notebook)(implicit contextShift: ContextShift[IO]): IO[LocalTestTransportServer] =
    IO(server)

  def connect(server: LocalTestTransportServer)(implicit contextShift: ContextShift[IO]): IO[LocalTestTransportClient] =
    IO(client)


  case class Expectation(
    expected: ScalaQueue[Either[PartialFunction[RemoteRequest, Boolean], PartialFunction[RemoteResponse, Boolean]]] = ScalaQueue.empty
  ) {

    def request(fn: PartialFunction[RemoteRequest, Boolean]): Expectation = copy(expected = expected enqueue Left(fn.orElse[RemoteRequest, Boolean]({ case req => throw new MatchError(req) })))
    def response(fn: PartialFunction[RemoteResponse, Boolean]): Expectation = copy(expected = expected enqueue Right(fn.orElse[RemoteResponse, Boolean]({ case rep => throw new MatchError(rep) })))

  }

  def run(fn: IO[Unit] => IO[Unit]): IO[Unit] = {
    val awaitResult = complete.get.as(())
    val runner = fn(awaitResult)
    for {
      _      <- IO(this.runningExpectations = true)
      fiber  <- runner.start
      result <- complete.get
      _      <- fiber.join
      _      <- IO(this.runningExpectations = false)
      _      <- IO.fromEither(result)
    } yield ()
  }
}

class LocalTestTransportServer(transport: LocalTestTransport)(implicit contextShift: ContextShift[IO]) extends TransportServer {

  private val deferredClient = Deferred.tryable[IO, LocalTestTransportClient].unsafeRunSync()
  val isClosed: SignallingRef[IO, Boolean] = SignallingRef[IO, Boolean](false).unsafeRunSync()
  val requestsOut: Queue[IO, RemoteRequest] = Queue.unbounded[IO, RemoteRequest].unsafeRunSync()

  def connectClient(client: LocalTestTransportClient): IO[Unit] = deferredClient.complete(client)

  def responses: Stream[IO, RemoteResponse] = Stream.eval(deferredClient.get).flatMap {
    client => client.responsesOut.dequeue.interruptWhen(isClosed)
  }

  def sendRequest(req: RemoteRequest): IO[Unit] = transport.logReq(req) *> requestsOut.enqueue1(req)
  def close(): IO[Unit] = isClosed.set(true)
  def connected: IO[Unit] = deferredClient.get.as(())

  override def isConnected: IO[Boolean] = deferredClient.tryGet.flatMap {
    case Some(client) => client.isClosed.get.map(closed => !closed)
    case None => IO.pure(false)
  }
}

class LocalTestTransportClient(server: LocalTestTransportServer, transport: LocalTestTransport)(implicit contextShift: ContextShift[IO]) extends TransportClient {
  server.connectClient(this).unsafeRunSync()
  val responsesOut: Queue[IO, RemoteResponse] = Queue.unbounded[IO, RemoteResponse].unsafeRunSync()
  val isClosed: SignallingRef[IO, Boolean] = SignallingRef[IO, Boolean](false).unsafeRunSync()

  def sendResponse(rep: RemoteResponse): IO[Unit] = transport.logRep(rep) *> responsesOut.enqueue1(rep)
  def requests: Stream[IO, RemoteRequest] = server.requestsOut.dequeue.interruptWhen(isClosed)
  def close(): IO[Unit] = isClosed.set(true)
}