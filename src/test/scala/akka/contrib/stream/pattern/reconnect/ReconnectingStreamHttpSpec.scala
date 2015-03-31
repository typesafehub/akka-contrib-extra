/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.Http
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.{ TestKitExtension, TestProbe }
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.languageFeature.postfixOps
import scala.util.control.NoStackTrace

class ReconnectingStreamHttpSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures {

  import scala.concurrent.duration._

  implicit val system = ActorSystem("test")

  implicit val timeout: Timeout = TestKitExtension(system).DefaultTimeout

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination(timeout.duration)
  }

  final val Localhost = new InetSocketAddress("127.0.0.1", 80)

  implicit val mat = ActorFlowMaterializer()

  "ReconnectingStreamHttp" must {

    "keep trying to connect" in {
      val p = TestProbe()

      val reconnectInterval = 200 millis
      val maxRetries = 4

      system.eventStream.subscribe(p.ref, classOf[Logging.Info])

      // notice how we keep the APIs similar:
      // val singleConnection = Http().outgoingConnection(Localhost)
      val initial = Http.reconnecting.outgoingConnection(Localhost.getHostName, reconnectInterval, maxRetries) { connected =>
        Source.failed(new TestException("Acting as if unable to connect!"))
          .via(connected.flow)
          .runWith(Sink.ignore)
      }

      p.expectMsgType[Logging.Info].message.toString should startWith(s"Opening initial connection to: $Localhost")
      (1 to maxRetries) foreach { _ =>
        p.expectMsgType[Logging.Info].message.toString should startWith(s"Connection to $Localhost was closed abruptly, reconnecting!")
        p.expectMsgType[Logging.Info].message.toString should startWith(s"Reconnecting to $Localhost")
      }

      p.expectNoMsg(2.seconds)

      initial.futureValue // it must be completed
    }
  }

  class TestException(msg: String) extends Exception(msg) with NoStackTrace
}
