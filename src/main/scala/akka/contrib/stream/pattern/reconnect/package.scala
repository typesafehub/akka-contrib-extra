package akka.contrib.stream.pattern

import akka.http.Http
import akka.stream.scaladsl.StreamTcp

package object reconnect {

  implicit final class ReconnectHttp(val h: Http.type) extends AnyVal {
    def reconnecting = ReconnectingStreamHttp
  }

  implicit final class ReconnectTcp(val h: StreamTcp.type) extends AnyVal {
    def reconnecting = ReconnectingStreamTcp
  }
}
