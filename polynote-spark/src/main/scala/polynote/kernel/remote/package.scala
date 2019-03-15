package polynote.kernel

import java.net.{InetSocketAddress, URI}

import cats.effect.IO

package object remote {

  def parseHostPort(string: String): IO[InetSocketAddress] = for {
    uri  <- IO(new URI(s"proto://$string"))
    host <- IO { Option(uri.getHost).getOrElse(throw new IllegalStateException("Remote host must be defined")) }
    port <- IO { Option(uri.getPort).getOrElse(throw new IllegalStateException("Remote port must be defined")) }
  } yield InetSocketAddress.createUnresolved(host, port)

}
