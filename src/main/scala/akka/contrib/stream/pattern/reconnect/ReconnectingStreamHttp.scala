/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.stream.pattern.reconnect

import akka.actor._
import akka.contrib.stream.pattern.reconnect.Reconnector._
import akka.http.Http
import akka.http.Http.OutgoingConnection
import akka.http.engine.client.ClientConnectionSettings
import akka.http.model.{ HttpRequest, HttpResponse }
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

/**
 * Automatically handles re-connecting if a HTTP connection is closed.
 */
object ReconnectingStreamHttp {

  /**
   * Used for tracking reconnection state (counters) of connections.
   */
  final case class HttpReconnectionData(
      address: InetSocketAddress,
      interval: FiniteDuration,
      retriesRemaining: Long,
      settings: Option[ClientConnectionSettings],
      onConnection: (HttpConnected => Unit)) extends ReconnectionData[HttpConnected] {
    def decrementRetryCounter: HttpReconnectionData = copy(retriesRemaining = retriesRemaining - 1)
  }

  /**
   * Offered as callback argument when a connection is established, may be useful to extract new materialized values etc.
   *
   * @param flow representing the http connection
   * @param reconnectionCancellable used to stop this connection from being re-established (when you want to cleanly close it)
   */
  final case class HttpConnected(flow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]], reconnectionCancellable: Cancellable)
    extends ConnectionStatus

  /**
   * Creates an actor monitored resilient connection which will be reconnected in given intervals in case of failure.
   *
   * The returned future will be completed for the initial connection,
   * for subsequent reconnections (in case of stream failure etc) the given `onConnection` callback will be called.
   * The future will be completed with either [[HttpConnected]] or [[InitialConnectionFailed]],
   * in either cases a cancellable is provided which you can use to shut down the backing reconnector actor.
   */
  // format: OFF
  def outgoingConnection(host: String,
                         port: Int, reconnectInterval: FiniteDuration,
                         settings: Option[ClientConnectionSettings],
                         maxRetries: Long = Long.MaxValue)
                        (onConnection: HttpConnected => Unit)
                        (implicit sys: ActorSystem, mat: FlowMaterializer): Future[ConnectionStatus] = {
    // format: ON
    require(maxRetries >= 0, "maxRetries must be >= 0")
    import sys.dispatcher

    val data = HttpReconnectionData(new InetSocketAddress(host, port), reconnectInterval, maxRetries, settings, onConnection)
    implicit val timeout = Timeout(data.interval * maxRetries)

    val reconnector = sys.actorOf(Props(new HttpReconnector(data, mat)))
    (reconnector ? InitialConnect).mapTo[ConnectionStatus].recover {
      case f: Throwable => InitialConnectionFailed(data.retriesRemaining, reconnector)
    }
  }

  private class HttpReconnector(initialData: HttpReconnectionData, implicit val mat: FlowMaterializer)
      extends Reconnector[HttpConnected, HttpReconnectionData](initialData) {
    import context.system

    override def connect(data: HttpReconnectionData): HttpConnected = {
      val host = data.address.getHostName
      val port = data.address.getPort
      val connection = Http().outgoingConnection(host, port, settings = initialData.settings)

      HttpConnected(connection, StopSelf)
    }
  }
}
