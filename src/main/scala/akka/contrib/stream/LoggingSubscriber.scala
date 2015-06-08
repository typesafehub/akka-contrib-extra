/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.stream

import akka.actor.{ ActorLogging, Props }
import akka.event.Logging
import akka.stream.actor.ActorSubscriberMessage.{ OnNext, OnError, OnComplete }
import akka.stream.actor.{ OneByOneRequestStrategy, RequestStrategy, ActorSubscriber }
import akka.util.ByteString

import scala.annotation.tailrec

/**
 * Companion for [[LoggingSubscriber]] – defines a Props factory.
 */
object LoggingSubscriber {
  val OneMegaByte: Int = 1024 * 1024
  /**
   * Create Props for an [[LoggingSubscriber]].
   *
   * @param level the `akka.event.Logging.LogLevel` used for logging the received strings
   * @param separator the `String` representing the separator, defaults to \n
   * @param maximumLineBytes an `int` used to limit the maximum allowed length of a line (in number of bytes),
   *                         defaults to 1MB. Over this limit the line is logged as is and the buffer is reset.
   * @return Props for an [[LoggingSubscriber]]
   */
  def props(level: Logging.LogLevel, separator: String = "\n", maximumLineBytes: Int = OneMegaByte): Props =
    Props(new LoggingSubscriber(level, separator, maximumLineBytes))
}

/**
 * Subscribes to a reactive stream of ByteStrings, parses lines and logs them at the requested level
 *
 * @param level the `akka.event.Logging.LogLevel` used for logging the received strings
 * @param separator the `String` representing the separator
 * @param maximumLineBytes an `int` used to limit the maximum allowed length of a line (in number of bytes).
 *                         Over this limit the line is logged as is and the buffer is reset.
 */
class LoggingSubscriber(level: Logging.LogLevel, separator: String, maximumLineBytes: Int)
    extends ActorSubscriber
    with ActorLogging {

  override protected def requestStrategy: RequestStrategy =
    OneByOneRequestStrategy

  private val separatorBytes = ByteString(separator)
  private val firstSeparatorByte = separatorBytes.head
  private var buffer = ByteString.empty
  private var nextPossibleMatch = 0

  override def receive: Receive = {
    case OnNext(element: ByteString) => onNext(element)
    case OnComplete                  => onComplete()
    case OnError(cause)              => onError(cause)
  }

  private def onNext(element: ByteString): Unit = {
    buffer ++= element
    doParse(Vector.empty).foreach(line => log.log(level, line))
    if (buffer.size > maximumLineBytes) {
      val line = buffer.utf8String
      log.log(level, line)
      buffer = ByteString.empty
    }
  }

  @tailrec
  private def doParse(parsedLinesSoFar: Vector[String]): Vector[String] = {
    val possibleMatchPos = buffer.indexOf(firstSeparatorByte, from = nextPossibleMatch)
    if (possibleMatchPos == -1) {
      // No matching character, we need to accumulate more bytes into the buffer
      nextPossibleMatch = buffer.size
      parsedLinesSoFar
    } else {
      if (possibleMatchPos + separatorBytes.size > buffer.size) {
        // We have found a possible match (we found the first character of the terminator
        // sequence) but we don't have yet enough bytes. We remember the position to
        // retry from next time.
        nextPossibleMatch = possibleMatchPos
        parsedLinesSoFar
      } else {
        if (buffer.slice(possibleMatchPos, possibleMatchPos + separatorBytes.size)
          == separatorBytes) {
          // Found a match
          val parsedLine = buffer.slice(0, possibleMatchPos).utf8String
          buffer = buffer.drop(possibleMatchPos + separatorBytes.size)
          nextPossibleMatch -= possibleMatchPos + separatorBytes.size
          doParse(parsedLinesSoFar :+ parsedLine)
        } else {
          nextPossibleMatch += 1
          doParse(parsedLinesSoFar)
        }
      }
    }

  }

  private def onComplete(): Unit =
    context.stop(self)

  private def onError(cause: Throwable): Unit = {
    log.error(cause, "Stopping because of upstream error!")
    context.stop(self)
  }
}
