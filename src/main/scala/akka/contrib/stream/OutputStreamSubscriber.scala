/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream

import akka.actor.{ ActorLogging, Props }
import akka.stream.actor.ActorSubscriberMessage.{ OnError, OnComplete, OnNext }
import akka.stream.actor.{ RequestStrategy, OneByOneRequestStrategy, ActorSubscriber }
import akka.util.ByteString
import java.io.{ IOException, OutputStream }
import scala.concurrent.blocking

/**
 * Companion for [[OutputStreamSubscriber]] – defines a Props factory.
 */
object OutputStreamSubscriber {

  /**
   * Create Props for an [[OutputStreamSubscriber]].
   * @param out the `java.io.OutputStream` used for writing
   * @return Props for an [[OutputStreamSubscriber]]
   */
  def props(out: OutputStream): Props =
    Props(new OutputStreamSubscriber(out))
}

/**
 * Subscribes to a reactive stream of ByteStrings and writes elements to a `java.io.OutputStream`.
 * @param out the `java.io.OutputStream` used for writing
 */
class OutputStreamSubscriber(out: OutputStream) extends ActorSubscriber with ActorLogging {

  override protected val requestStrategy: RequestStrategy =
    OneByOneRequestStrategy

  override def receive: Receive = {
    case OnNext(element: ByteString) => onNext(element)
    case OnComplete                  => onComplete()
    case OnError(cause)              => onError(cause)
  }

  override def postStop(): Unit = {
    out.close()
    super.postStop()
  }

  private def onNext(element: ByteString): Unit =
    try
      blocking {
        out.write(element.toArray)
        out.flush() // Despite lack of documentation, this seems to be essential for stdin!
      }
    catch {
      case e: IOException =>
        cancel()
        throw e
    }

  private def onComplete(): Unit =
    context.stop(self)

  private def onError(cause: Throwable): Unit = {
    log.error(cause, "Stopping because of upstream error!")
    context.stop(self)
  }
}
