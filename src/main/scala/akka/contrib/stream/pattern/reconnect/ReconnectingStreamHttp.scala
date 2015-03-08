/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.contrib.stream.pattern.reconnect.Reconnector._
import akka.event.LoggingAdapter
import akka.http.Http
import akka.http.Http.OutgoingConnection
import akka.http.engine.client.ClientConnectionSettings
import akka.http.model.{ HttpRequest, HttpResponse }
import akka.io.Inet
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

import scala.collection.immutable
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
      localAddress: Option[InetSocketAddress],
      options: immutable.Traversable[Inet.SocketOption],
      settings: Option[ClientConnectionSettings],
      log: LoggingAdapter,
      onConnection: (HttpConnected => Unit)) extends ReconnectionData[HttpConnected] {
    def decrementRetryCounter: HttpReconnectionData = copy(retriesRemaining = retriesRemaining - 1)
  }

  /**
   * Offered as callback argument when a connection is established, may be useful to extract new materialized values etc.
   *
   * @param flow representing the http connection (with applied reconnection logic)
   * @param cancelReconnections used to stop this connection from being re-established (when you want to cleanly close it)
   */
  final case class HttpConnected(flow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]], cancelReconnections: () => Unit)
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
                         reconnectInterval: FiniteDuration,
                         maxRetries: Int = Int.MaxValue,
                         port: Int = 80,
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[Inet.SocketOption] = Nil,
                         settings: Option[ClientConnectionSettings] = None,
                         log: Option[LoggingAdapter] = None)
                        (onConnection: HttpConnected => Unit)
                        (implicit sys: ActorSystem, mat: FlowMaterializer): Future[ConnectionStatus] = {
    // format: ON
    require(maxRetries >= 0, "maxRetries must be >= 0")
    import sys.dispatcher
    val data = HttpReconnectionData(new InetSocketAddress(host, port), reconnectInterval, maxRetries, localAddress, options, settings, log.getOrElse(sys.log), onConnection)
    implicit val timeout = Reconnector.connectionTimeout(sys)

    val reconnector = sys.actorOf(Props(new HttpReconnector(data, mat)), nextName())
    (reconnector ? InitialConnect).mapTo[ConnectionStatus].recover {
      case f: Throwable => InitialConnectionFailed(data.retriesRemaining, reconnector)
    }
  }

  private val counter = new AtomicLong()
  private def nextName(): String =
    s"reconnector-http-${counter.incrementAndGet()}" // TODO include host/port (needs encoding of host name)

  private class HttpReconnector(initialData: HttpReconnectionData, implicit val mat: FlowMaterializer)
      extends Reconnector[HttpConnected, HttpReconnectionData](initialData) {
    import context.system

    override def connect(data: HttpReconnectionData): HttpConnected = {
      val host = data.address.getHostName
      val port = data.address.getPort

      val connection = Http().outgoingConnection(
        host, port,
        settings = initialData.settings,
        localAddress = initialData.localAddress,
        options = initialData.options,
        log = initialData.log)

      val reconnectingConnection = connection.transform(() => new ReconnectStage(data))

      HttpConnected(reconnectingConnection, StopSelf)
    }
  }
}
