/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.process

import akka.actor._
import akka.stream.scaladsl.{ FlowGraph, ImplicitFlowMaterializer, Merge, Sink, Source }
import akka.testkit.TestProbe
import akka.util.ByteString
import java.io.File
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.collection.immutable
import scala.concurrent.duration.DurationInt

class BlockingProcessSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  implicit val system = ActorSystem("test", testConfig)

  "A BlockingProcess" should {
    "read from stdin and write to stdout" in {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val probe = TestProbe()
      val stdinInput = List("abcd", "1234", "quit")
      val receiver = system.actorOf(Props(new Receiver(probe.ref, stdinInput)), "receiver1")
      system.actorOf(BlockingProcess.props(receiver, List(command)), "process1")

      var partiallyReceived = false
      probe.expectMsgPF(5.seconds) {
        case Receiver.Out("abcd1234") =>
          true
        case Receiver.Out("abcd") =>
          partiallyReceived = true
          true
      }
      if (partiallyReceived) {
        probe.expectMsg(Receiver.Out("1234"))
      }
      probe.expectMsg(BlockingProcess.Exited(0))
    }

    "allow a blocking process that is blocked to be destroyed" in {
      val command = getClass.getResource("/sleep.sh").getFile
      new File(command).setExecutable(true)

      val probe = TestProbe()
      val receiver = system.actorOf(Props(new Receiver(probe.ref, List.empty)), "receiver2")
      val process = system.actorOf(BlockingProcess.props(receiver, List(command)), "process2")

      probe.watch(process)

      probe.expectMsg(Receiver.Out("Starting"))

      system.stop(process)

      probe.expectTerminated(process)
    }
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }
}

object Receiver {
  case class Out(s: String)
  case class Err(s: String)
}

class Receiver(probe: ActorRef, stdinInput: immutable.Seq[String]) extends Actor
    with Stash
    with ImplicitFlowMaterializer {

  import FlowGraph.Implicits._
  import Receiver._
  import context.dispatcher

  override def receive: Receive = {
    case BlockingProcess.Started(stdin, stdout, stderr) =>
      Source(stdinInput)
        .map(ByteString.apply)
        .runWith(Sink(stdin))
      val stdoutFlow = Source(stdout).map(element => Out(element.utf8String))
      val stderrFlow = Source(stderr).map(element => Err(element.utf8String))
      val tellProbeSink = Sink.foreach(probe.!)
      val graph =
        FlowGraph.closed(tellProbeSink) { implicit b =>
          resultSink =>
            val merge = b.add(Merge[AnyRef](inputPorts = 2))
            stdoutFlow ~> merge.in(0)
            stderrFlow ~> merge.in(1)
            merge ~> resultSink
        }
      graph
        .run()
        .onComplete(_ => self ! "flow-complete")

    case "flow-complete" =>
      unstashAll()
      context become {
        case exited: BlockingProcess.Exited => probe ! exited
      }
    case _ =>
      stash()
  }
}
