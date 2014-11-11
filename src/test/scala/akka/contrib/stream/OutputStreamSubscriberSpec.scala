/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream

import akka.actor.ActorSystem
import akka.stream.actor.ActorSubscriber
import akka.stream.actor.ActorSubscriberMessage.{ OnError, OnComplete, OnNext }
import akka.testkit.TestProbe
import akka.util.ByteString
import java.io.OutputStream
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

class OutputStreamSubscriberSpec extends WordSpec with Matchers with BeforeAndAfterAll with MockitoSugar {

  implicit val system = ActorSystem("test", testConfig)

  "Sending ActorSubscriber.OnNext with a ByteString to an OutputStreamSubscriber" should {
    "result in writing the respective bytes to the OutputStream" in {
      val out = mock[OutputStream]
      val bytes = Array(1 to 10 map (_.toByte): _*)
      val outputStreamSubscriber = system.actorOf(OutputStreamSubscriber.props(out))
      outputStreamSubscriber ! OnNext(ByteString.fromArray(bytes))
      TestProbe().awaitAssert {
        verify(out).write(bytes)
      }
    }
  }

  "Sending ActorSubscriber.OnComplete to an an OutputStreamSubscriber" should {
    "result in the stopping the OutputStreamSubscriber and closing the OutputStream" in {
      val probe = TestProbe()
      val out = mock[OutputStream]
      val outputStreamSubscriber = system.actorOf(OutputStreamSubscriber.props(out))
      probe.watch(outputStreamSubscriber)
      outputStreamSubscriber ! OnComplete
      probe.expectTerminated(outputStreamSubscriber)
      probe.awaitAssert {
        verify(out).close()
      }
    }
  }

  "Sending ActorSubscriber.OnError to an an OutputStreamSubscriber" should {
    "result in closing the OutputStream" in {
      val out = mock[OutputStream]
      val outputStreamSubscriber = system.actorOf(OutputStreamSubscriber.props(out))
      outputStreamSubscriber ! OnError(new Exception("By purpose!"))
      TestProbe().awaitAssert {
        verify(out).close()
      }
    }
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }
}
