/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream.pattern.reconnect

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.contrib.stream.pattern.reconnect.ReconnectingStreamTcp.TcpConnected
import akka.contrib.stream.pattern.reconnect.Reconnector.{ InitialConnectionFailed, ConnectionStatus }
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Flow
import akka.testkit.{ TestKitExtension, TestProbe }
import akka.util.{ ByteString, Timeout }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.languageFeature.postfixOps
import scala.util.{ Failure, Success }

class ReconnectingStreamTcpTest extends WordSpecLike with Matchers with BeforeAndAfterAll {

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

      val handler = Flow[ByteString]

      val reconnectInterval = 200 millis
      val initialConnection = 1
      val maxRetries = 4

      ReconnectingStreamTcp.outgoingConnection[Unit](InSpaceNoOneCanHearYouConnect, handler, reconnectInterval, maxRetries)(p.ref ! _)

      (1 to (initialConnection + maxRetries)) foreach { _ => p.expectMsgType[TcpConnected[_]] }

      p.expectNoMsg(2.seconds)
    }
  }

  "allow multiple connections with their own retries to the same address" in {
    val p1, p2 = TestProbe()

    val handler = Flow[ByteString]

    val host1 = new InetSocketAddress("127.0.0.1", 1)
    val host2 = new InetSocketAddress("127.0.0.1", 2)

    val reconnectInterval = 200 millis
    val initialConnection = 1
    val maxRetries1 = 2
    val maxRetries2 = 5

    ReconnectingStreamTcp.outgoingConnection[Unit](host1, handler, reconnectInterval, maxRetries1)(p1.ref ! _)
    ReconnectingStreamTcp.outgoingConnection[Unit](host2, handler, reconnectInterval, maxRetries2)(p2.ref ! _)

    (1 to (initialConnection + maxRetries1)) foreach { _ => p1.expectMsgType[TcpConnected[_]] }
    (1 to (initialConnection + maxRetries2)) foreach { _ => p2.expectMsgType[TcpConnected[_]] }

    p1.expectNoMsg(1.second)
    p2.expectNoMsg(1.second)
  }

  "allow using the same target address by multiple users, with different reconnect settings" in pending // TODO implement this test

  "reconnect if the connection breaks after being established" in pending // TODO implement a proper test for this

}
