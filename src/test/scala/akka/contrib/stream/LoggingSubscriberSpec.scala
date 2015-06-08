/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.stream

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.Logging.LogEvent
import akka.stream.actor.ActorSubscriberMessage.{ OnError, OnComplete, OnNext }
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

class LoggingSubscriberSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  implicit val system = ActorSystem("test", testConfig)

  "Sending ActorSubscriber.OnNext with a ByteString representing a single line to a LoggingSubscriber" should {
    "result in writing the single line to the logs" in {
      // Given
      val logLine: String = "first line"
      val loggingSubscriber = system.actorOf(LoggingSubscriber.props(Logging.InfoLevel))
      val logSource = loggingSubscriber.path.toString
      val probe: TestProbe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[LogEvent])

      //When
      loggingSubscriber ! OnNext(ByteString(logLine + "\n"))

      // Then
      probe.fishForMessage() {
        case loggedLine @ Logging.Info(`logSource`, clazz, _) if clazz == classOf[LoggingSubscriber] => true
      } shouldEqual Logging.Info(logSource, classOf[LoggingSubscriber], logLine)
    }
  }

  "Sending ActorSubscriber.OnComplete to an an LoggingSubscriber" should {
    "result in the stopping the LoggingSubscriber" in {
      val probe = TestProbe()
      val loggingSubscriber = system.actorOf(LoggingSubscriber.props(Logging.InfoLevel))
      probe.watch(loggingSubscriber)
      loggingSubscriber ! OnComplete
      probe.expectTerminated(loggingSubscriber)
    }
  }

  "Sending ActorSubscriber.OnError to an an OutputStreamSubscriber" should {
    "result in closing the OutputStream" in {
      val loggingSubscriber = system.actorOf(LoggingSubscriber.props(Logging.InfoLevel))
      loggingSubscriber ! OnError(new Exception("By purpose!"))
    }
  }

  "Sending ActorSubscriber.OnNext with a ByteString representing two lines to a LoggingSubscriber" should {
    "result in writing both lines to the logs in separate messages" in {
      // Given
      val firstLine: String = "first line"
      val secondLine: String = "second line"
      val loggingSubscriber = system.actorOf(LoggingSubscriber.props(Logging.InfoLevel))
      val logSource = loggingSubscriber.path.toString
      val probe: TestProbe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[LogEvent])

      //When
      loggingSubscriber ! OnNext(ByteString(firstLine + "\n" + secondLine + "\n"))

      // Then
      probe.fishForMessage() {
        case loggedLine @ Logging.Info(`logSource`, clazz, _) if clazz == classOf[LoggingSubscriber] => true
      } shouldEqual Logging.Info(logSource, classOf[LoggingSubscriber], firstLine)

      probe.fishForMessage() {
        case loggedLine @ Logging.Info(`logSource`, clazz, _) if clazz == classOf[LoggingSubscriber] => true
      } shouldEqual Logging.Info(logSource, classOf[LoggingSubscriber], secondLine)
    }
  }
  "Sending ActorSubscriber.OnNext with two ByteStrings representing a line to a LoggingSubscriber" should {
    "result in writing the line to the logs in a signle messages" in {
      // Given
      val first: String = "first"
      val second: String = " line"
      val loggingSubscriber = system.actorOf(LoggingSubscriber.props(Logging.InfoLevel))
      val logSource = loggingSubscriber.path.toString

      val probe: TestProbe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[LogEvent])

      //When
      loggingSubscriber ! OnNext(ByteString(first))
      loggingSubscriber ! OnNext(ByteString(second + "\n"))

      // Then
      probe.fishForMessage() {
        case loggedLine @ Logging.Info(`logSource`, clazz, _) if clazz == classOf[LoggingSubscriber] => true
      } shouldEqual Logging.Info(logSource, classOf[LoggingSubscriber], "first line")
    }
  }
  "Sending ActorSubscriber.OnNext with three ByteStrings representing a line longer than maximumLineLength to a LoggingSubscriber" should {
    "result in writing the line to the logs in separate messages" in {
      // Given
      val first: String = "first"
      val line: String = " line"
      val loggingSubscriber = system.actorOf(LoggingSubscriber.props(Logging.InfoLevel, maximumLineBytes = 1))
      val logSource = loggingSubscriber.path.toString

      val probe: TestProbe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[LogEvent])

      //When
      loggingSubscriber ! OnNext(ByteString(first))
      loggingSubscriber ! OnNext(ByteString(line))
      loggingSubscriber ! OnNext(ByteString("\\"))

      // Then
      probe.fishForMessage() {
        case loggedLine @ Logging.Info(`logSource`, clazz, _) if clazz == classOf[LoggingSubscriber] => true
      } shouldEqual Logging.Info(logSource, classOf[LoggingSubscriber], first)

      probe.fishForMessage() {
        case loggedLine @ Logging.Info(`logSource`, clazz, _) if clazz == classOf[LoggingSubscriber] => true
      } shouldEqual Logging.Info(logSource, classOf[LoggingSubscriber], line)
    }
  }
}
