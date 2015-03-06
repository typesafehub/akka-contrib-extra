/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress

import akka.actor.{ ActorSystem, Cancellable, Props }
import akka.contrib.stream.pattern.reconnect.Reconnector._
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ StreamTcp, Flow }
import akka.stream.stage.{ Context, Directive, PushStage, TerminationDirective }
import akka.util.{ ByteString, Timeout }

import scala.concurrent.{ Promise, Future }
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

/**
 * Automatically handles re-connecting if a TCP connection is closed.
 */
object ReconnectingStreamTcp {

  /**
   * Used for tracking reconnection state (counters) of connections.
   */
  case class TcpReconnectionData[M](
      address: InetSocketAddress,
      handler: Flow[ByteString, ByteString, M],
      interval: FiniteDuration,
      retriesRemaining: Long,
      onConnection: (ConnectionStatus => Unit)) extends ReconnectionData[TcpConnected[M]] {
    override def decrementRetryCounter = copy(retriesRemaining = retriesRemaining - 1)
  }

  /**
   * Offered as callback argument when a connection is established, may be useful to extract new materialized values etc.
   *
   * @param materialized the materialized values of this streams incarnation (different each time the stream reconnects)
   * @param reconnectionCancellable used to stop this connection from being re-established (when you want to cleanly close it)
   */
  final case class TcpConnected[M](materialized: M, reconnectionCancellable: Cancellable) extends ConnectionStatus

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
  def outgoingConnection[M](address: InetSocketAddress, handleWith: Flow[ByteString, ByteString, M], reconnectInterval: FiniteDuration, maxRetries: Long = Long.MaxValue)
                           (onConnection: (ConnectionStatus => Unit))
                           (implicit sys: ActorSystem, mat: FlowMaterializer): Future[ConnectionStatus] = {
    // format: ON
    require(maxRetries >= 0, "maxRetries must be >= 0")
    import sys.dispatcher

    val data = TcpReconnectionData[M](address, handleWith, reconnectInterval, maxRetries, onConnection)
    implicit val timeout = Timeout(data.interval * maxRetries)

    val reconnector = sys.actorOf(Props(new TcpReconnector(data, mat)))
    (reconnector ? InitialConnect).mapTo[ConnectionStatus].recover {
      case f: Throwable => InitialConnectionFailed(data.retriesRemaining, reconnector)
    }
  }

  private class TcpReconnector[M](initialData: TcpReconnectionData[M], implicit val mat: FlowMaterializer)
      extends Reconnector[TcpConnected[M], ReconnectionData[TcpConnected[M]]](initialData) {

    import context.system

    override def connect(data: ReconnectionData[TcpConnected[M]]): TcpConnected[M] = {
      val connection = StreamTcp().outgoingConnection(data.address, connectTimeout = data.interval) // TODO sure?
      val handlerWithReconnection = initialData.handler.transform(() => new ReconnectStage(data))
      val (_, materialized) = connection.join(handlerWithReconnection).run()

      TcpConnected(materialized, StopSelf)
    }

  }

}

