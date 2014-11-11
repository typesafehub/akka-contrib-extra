/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream

import akka.actor.ActorSystem
import akka.stream.actor.{ ActorPublisher, ActorSubscriber }
import akka.testkit.TestProbe
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.concurrent.duration.Duration
import scala.util.Random

class InputStreamPublisherOutputStreamSubscriberSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  private implicit val system = ActorSystem("test", testConfig)

  "An InputStreamPublisher publishing to an OutputStreamSubscriber" should {
    "write the contents of the input stream to the output stream" in {
      val bytes = Array.ofDim[Byte](1024 * 10)
      new Random(0).nextBytes(bytes)

      val in = new ByteArrayInputStream(bytes)
      val inputStreamPublisher = system.actorOf(InputStreamPublisher.props(in, Duration.Undefined))
      val publisher = ActorPublisher(inputStreamPublisher)

      val out = new ByteArrayOutputStream()
      val outputStreamSubscriber = system.actorOf(OutputStreamSubscriber.props(out))
      val subscriber = ActorSubscriber(outputStreamSubscriber)

      publisher.subscribe(subscriber)

      TestProbe().awaitAssert {
        out.toByteArray shouldEqual bytes
      }
    }
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }
}
