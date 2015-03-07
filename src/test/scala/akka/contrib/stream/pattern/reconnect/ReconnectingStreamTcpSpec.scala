/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.contrib.stream.pattern.reconnect.ReconnectingStreamTcp.TcpConnected
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.{ Sink, Source, StreamTcp }
import akka.testkit.{ TestKitExtension, TestProbe }
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.languageFeature.postfixOps
import scala.util.control.NoStackTrace

class ReconnectingStreamTcpSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures {

  import scala.concurrent.duration._

  implicit val system = ActorSystem("test")

  implicit val timeout: Timeout = TestKitExtension(system).DefaultTimeout

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination(timeout.duration)
  }

  // assumption: no-one listening on this port
  val InSpaceNoOneCanHearYouConnect = new InetSocketAddress("127.0.0.1", 0)

  implicit val mat = ActorFlowMaterializer()

  "ReconnectingStreamTcp" must {

    "keep trying to connect" in {
      val p = TestProbe()

      val reconnectInterval = 200 millis
      val initialConnection = 1
      val maxRetries = 4

      // notice how we keep the APIs similar:
      // val singleConnection = StreamTcp().outgoingConnection(InSpaceNoOneCanHearYouConnect)
      val initial = StreamTcp.reconnecting.outgoingConnection(InSpaceNoOneCanHearYouConnect, reconnectInterval, maxRetries) { connected =>
        Source.failed(new TestException("Acting as if unable to connect!"))
          .via(connected.flow)
          .runWith(Sink.ignore())
        p.ref ! connected
      }

      (1 to (initialConnection + maxRetries)) foreach { _ => p.expectMsgType[TcpConnected] }

      p.expectNoMsg(2.seconds)
      initial.futureValue // must be completed
    }
  }

  "allow multiple connections with their own retries to the same address" in {
    val p1, p2 = TestProbe()

    val host1 = new InetSocketAddress("127.0.0.1", 1)
    val host2 = new InetSocketAddress("127.0.0.1", 2)

    val reconnectInterval = 200 millis
    val initialConnection = 1
    val maxRetries1 = 2
    val maxRetries2 = 5

    StreamTcp.reconnecting.outgoingConnection(host1, reconnectInterval, maxRetries1) { connected =>
      Source.failed(new TestException("Acting as if unable to connect!"))
        .via(connected.flow)
        .runWith(Sink.ignore())
      p1.ref ! connected
    }
    StreamTcp.reconnecting.outgoingConnection(host2, reconnectInterval, maxRetries2) { connected =>
      Source.failed(new TestException("Acting as if unable to connect!"))
        .via(connected.flow)
        .runWith(Sink.ignore())
      p2.ref ! connected
    }

    (1 to (initialConnection + maxRetries1)) foreach { _ => p1.expectMsgType[TcpConnected] }
    (1 to (initialConnection + maxRetries2)) foreach { _ => p2.expectMsgType[TcpConnected] }

    p1.expectNoMsg(1.second)
    p2.expectNoMsg(1.second)
  }

  "allow using the same target address by multiple users, with different reconnect settings" in pending // TODO implement this test

  "reconnect if the connection breaks after being established" in pending // TODO implement a proper test for this

  class TestException(msg: String) extends Exception(msg) with NoStackTrace
}
