/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress

import akka.actor._
import Reconnector._
import akka.stream.FlowMaterializer
import akka.stream.stage.{ Context, Directive, PushStage, TerminationDirective }
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration

object Reconnector {

  def props[T](data: ReconnectionData[T], mat: FlowMaterializer): Props =
    Props(classOf[Reconnector[_, _]], data, mat)

  trait ReconnectionData[T] {
    def address: InetSocketAddress

    def interval: FiniteDuration
    def retriesRemaining: Long

    def onConnection: (ConnectionStatus => Unit)

    def decrementRetryCounter: ReconnectionData[T]
  }

  trait ConnectionStatus

  /**
   * Completes the initial connection's future in case the initial connection fails to connect.
   * Can be used to abandon trying to further connect by using the provided cancellable.
   */
  final case class InitialConnectionFailed(remainingRetries: Long, private val actorRef: ActorRef) extends ConnectionStatus {
    def reconnectionCancellable = new Cancellable {
      override def isCancelled: Boolean = remainingRetries > 0
      override def cancel(): Boolean = {
        actorRef ! PoisonPill
        true
      }
    }
  }

  private[reconnect] case object InitialConnect
  private[reconnect] case object Reconnect
}

/**
 * Contains logic of issuing connect() calls within expected intervals if a connection terminates
 */
abstract class Reconnector[T, D <: ReconnectionData[T]](initialData: D)
    extends Actor with ActorLogging {

  import context.dispatcher

  private var data: D = initialData

  def connect(data: D): T

  override def receive: Receive = {
    case InitialConnect =>
      log.info("Opening initial connection to: {}", data)
      val connected = connect(data)
      data.onConnection(connected.asInstanceOf[ConnectionStatus])
      sender() ! connected

    case Reconnect if data.retriesRemaining > 0 =>
      data = data.decrementRetryCounter.asInstanceOf[D]
      log.info("Reconnecting to {}, {} retries remaining", data.address, data.retriesRemaining)
      data.onConnection(connect(data).asInstanceOf[ConnectionStatus])

    case Reconnect =>
      log.warning("Abandoning reconnecting to {} after {} retries!", data.address, initialData.retriesRemaining)
      context stop self
  }

  final val StopSelf = new Cancellable {
    override def isCancelled: Boolean = false
    override def cancel(): Boolean = {
      log.info("Cancelling reconnection logic for {}", data.address)
      self ! PoisonPill
      true
    }
  }

  /** Schedules an reconnect event when the upstream finishes (propagating the finishing) */
  final class ReconnectStage[M](data: ReconnectionData[M])(implicit sys: ActorSystem, mat: FlowMaterializer) extends PushStage[ByteString, ByteString] {

    override def onPush(elem: ByteString, ctx: Context[ByteString]): Directive = ctx.push(elem)

    override def onUpstreamFailure(cause: Throwable, ctx: Context[ByteString]): TerminationDirective = {
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
