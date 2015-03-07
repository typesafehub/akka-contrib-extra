/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{ ActorSystem, Props }
import akka.contrib.stream.pattern.reconnect.Reconnector._
import akka.io.Inet.SocketOption
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Flow, StreamTcp }
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.language.postfixOps

/**
 * Automatically handles re-connecting if a TCP connection is closed.
 */
object ReconnectingStreamTcp {

  /**
   * Used for tracking reconnection state (counters) of connections.
   */
  case class TcpReconnectionData(
      address: InetSocketAddress,
      interval: FiniteDuration,
      retriesRemaining: Long,

      localAddress: Option[InetSocketAddress],
      options: immutable.Traversable[SocketOption],
      connectTimeout: Duration,
      idleTimeout: Duration,

      onConnection: (TcpConnected => Unit)) extends ReconnectionData[TcpConnected] {
    override def decrementRetryCounter = copy(retriesRemaining = retriesRemaining - 1)
  }

  /**
   * Offered as callback argument when a connection is established, may be useful to extract new materialized values etc.
   *
   * @param flow representing the Tcp connection (with applied reconnection logic)
   * @param cancelReconnections used to stop this connection from being re-established (when you want to cleanly close it)
   */
  final case class TcpConnected(flow: Flow[ByteString, ByteString, Future[StreamTcp.OutgoingConnection]], cancelReconnections: () => Unit) extends ConnectionStatus

  /**
   * Creates an actor monitored resilient connection which will be reconnected in given intervals in case of failure.
   *
   * The returned future will be completed for the initial connection,
   * for subsequent reconnections (in case of stream failure etc) the given `onConnection` callback will be called.
   * The future will be completed with either [[TcpConnected]] or [[InitialConnectionFailed]],
   * in either cases a cancellable is provided which you can use to shut down the backing reconnector actor.
   *
   */
  // format: OFF
  def outgoingConnection(address: InetSocketAddress,
                         reconnectInterval: FiniteDuration,
                         maxRetries: Long = Long.MaxValue,
                         localAddress: Option[InetSocketAddress] = None,
                         options: immutable.Traversable[SocketOption] = Nil,
                         connectTimeout: Duration = Duration.Inf,
                         idleTimeout: Duration = Duration.Inf)
                        (onConnection: (TcpConnected => Unit))
                        (implicit sys: ActorSystem, mat: FlowMaterializer): Future[ConnectionStatus] = {
    // format: ON
    require(maxRetries >= 0, "maxRetries must be >= 0")
    import sys.dispatcher

    val data = TcpReconnectionData(address, reconnectInterval, maxRetries, localAddress, options, connectTimeout, idleTimeout, onConnection)
    implicit val timeout = Reconnector.connectionTimeout(sys)

    val reconnector = sys.actorOf(Props(new TcpReconnector(data, mat)), nextName())
    (reconnector ? InitialConnect).mapTo[ConnectionStatus].recover {
      case f: Throwable => InitialConnectionFailed(data.retriesRemaining, reconnector)
    }
  }

  private val counter = new AtomicLong()
  private def nextName(): String =
    s"reconnector-tcp-${counter.incrementAndGet()}" // TODO include host/port (needs encoding of host name)

  private class TcpReconnector(initialData: TcpReconnectionData, implicit val mat: FlowMaterializer)
      extends Reconnector[TcpConnected, TcpReconnectionData](initialData) {

    import context.system

    override def connect(data: TcpReconnectionData): TcpConnected = {
      val connection = StreamTcp().outgoingConnection(data.address, data.localAddress, data.options, data.connectTimeout, data.idleTimeout)
      val reconnecting = connection.transform(() => new ReconnectStage(data))

      TcpConnected(reconnecting, StopSelf)
    }

  }

}

