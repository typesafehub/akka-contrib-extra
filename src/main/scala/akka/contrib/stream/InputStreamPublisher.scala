/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream

import akka.actor.{ ActorLogging, Props, ReceiveTimeout }
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.Request
import akka.util.ByteString
import java.io.{ IOException, InputStream }
import scala.annotation.tailrec
import scala.concurrent.blocking
import scala.concurrent.duration.Duration

/**
 * Companion for [[InputStreamPublisher]] – defines a Props factory.
 */
object InputStreamPublisher {

  /**
   * Create Props for an [[InputStreamPublisher]].
   * @param in the `java.io.InputStream` used for reading. The publisher is responsible for closing the stream.
   * @param receiveTimeout the maximum amount of time for this publisher to sit idle before stopping itself and cleaning
   *                       up.
   * @param bufferLength the length of the buffer used for reading; defaults to 8192
   * @return Props for an [[InputStreamPublisher]]
   */
  def props(in: InputStream, receiveTimeout: Duration, bufferLength: Int = 8192): Props =
    Props(new InputStreamPublisher(in, receiveTimeout, bufferLength))
}

/**
 * Publishes a reactive stream of ByteStrings read from a `java.io.InputStream`.
 */
class InputStreamPublisher(in: InputStream, receiveTimeout: Duration, bufferLength: Int)
    extends ActorPublisher[ByteString]
    with ActorLogging {

  private val buffer = Array.ofDim[Byte](bufferLength)

  override def preStart(): Unit =
    context.setReceiveTimeout(receiveTimeout)

  override def receive: Receive = {
    case Request(_)     => onRequest()
    case ReceiveTimeout => context.stop(self)
  }

  override def postStop(): Unit = {
    in.close()
    super.postStop()
  }

  private def onRequest(): Unit =
    try
      read()
    catch {
      case e: IOException =>
        onComplete()
        context.stop(self)
    }

  @tailrec
  private def read(): Unit = {
    val nrOfBytes = blocking(in.read(buffer))
    if (nrOfBytes != -1) {
      onNext(ByteString.fromArray(buffer, 0, nrOfBytes))
      if (totalDemand > 0) read()
    } else {
      onComplete()
      context.stop(self)
    }
  }
}
