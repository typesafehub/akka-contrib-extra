/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.contrib.stream.pattern.reconnect.Reconnector._
import akka.stream.FlowMaterializer
import akka.stream.stage.{ Context, Directive, PushStage, TerminationDirective }
import akka.util.Timeout

import scala.concurrent.duration.FiniteDuration

object Reconnector {

  def connectionTimeout(sys: ActorSystem) = {
    import scala.concurrent.duration._
    Timeout(sys.settings.config.getDuration("akka.http.client.connecting-timeout", TimeUnit.MILLISECONDS).millis)
  }

  trait ReconnectionData[T <: ConnectionStatus] {
    def address: InetSocketAddress

    def interval: FiniteDuration
    def retriesRemaining: Long

    def onConnection: (T => Unit)

    def decrementRetryCounter: ReconnectionData[T]
  }

  /**
   * Either [[InitialConnectionFailed]] or a successful connection (value depending on implementation).
   * Allows for externally stopping further retries from being issued.
   */
  trait ConnectionStatus {
    /**
     * Stops this connection from further performing reconnections.
     * Does NOT terminate an existing running connection if it exists - it should be terminated normally.
     */
    def cancelReconnections: () => Unit
  }

  /**
   * Completes the initial connection's future in case the initial connection fails to connect.
   * Can be used to abandon trying to further connect by using the provided cancellable.
   */
  final case class InitialConnectionFailed(remainingRetries: Long, private val actorRef: ActorRef) extends ConnectionStatus {
    override val cancelReconnections = () => actorRef ! PoisonPill
  }

  private[reconnect] case object InitialConnect extends DeadLetterSuppression
  private[reconnect] case object Reconnect extends DeadLetterSuppression

}

/**
 * Contains logic of issuing connect() calls within expected intervals if a connection terminates
 */
abstract class Reconnector[T <: ConnectionStatus, D <: ReconnectionData[T]](initialData: D)
    extends Actor with ActorLogging {

  import context.dispatcher

  private var data: D = initialData

  def connect(data: D): T

  override def receive: Receive = {
    case InitialConnect =>
      log.info("Opening initial connection to: {}", data.address)
      val connected = connect(data)
      data.onConnection(connected)
      sender() ! connected

    case Reconnect if data.retriesRemaining > 0 =>
      data = data.decrementRetryCounter.asInstanceOf[D]
      log.info("Reconnecting to {}, {} retries remaining", data.address, data.retriesRemaining)
      val connection = connect(data)
      data.onConnection(connection)

    case Reconnect =>
      log.warning("Abandoning reconnecting to {} after {} retries!", data.address, initialData.retriesRemaining)
      context stop self
  }

  final val StopSelf = () => {
    log.info("Cancelling reconnection logic for {}", data.address)
    self ! PoisonPill
  }

  /** Schedules an reconnect event when the upstream finishes (propagating the finishing) */
  final class ReconnectStage[A, M <: ConnectionStatus](data: ReconnectionData[M])(implicit sys: ActorSystem, mat: FlowMaterializer)
      extends PushStage[A, A] {

    override def onPush(elem: A, ctx: Context[A]): Directive = ctx.push(elem)

    override def onUpstreamFailure(cause: Throwable, ctx: Context[A]): TerminationDirective = {
      if (data.retriesRemaining > 0) {
        log.info("Connection to {} was closed abruptly, reconnecting! (retries remaining: {})", data.address, data.retriesRemaining - 1)
        context.system.scheduler.scheduleOnce(data.interval, self, Reconnect)
      } else {
        log.warning("Connection to {} was closed. Abandoning reconnections as maxRetries exceeded.", data.address)
      }
      ctx.finish()
    }
  }

}
