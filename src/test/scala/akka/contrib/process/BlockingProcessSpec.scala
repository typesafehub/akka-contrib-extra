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
      val process = system.actorOf(BlockingProcess.props(receiver, List(command)), "process1")

      val partiallyReceived =
        probe.expectMsgPF() {
          case Receiver.Out("abcd1234") =>
            false
          case Receiver.Out("abcd") =>
            true
        }

      if (partiallyReceived) {
        probe.expectMsg(Receiver.Out("1234"))
      }

      probe.expectMsgPF() {
        case BlockingProcess.Exited(x) => x
      } shouldEqual 0

      probe.watch(process)
      probe.expectTerminated(process)
    }

    "allow a blocking process that is blocked to be destroyed" in {
      expectDestruction(viaDestroy = true)
    }

    "allow a blocking process that is blocked to be stopped" in {
      expectDestruction(viaDestroy = false)
    }
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }

  def expectDestruction(viaDestroy: Boolean): Unit = {
    val command = getClass.getResource("/sleep.sh").getFile
    new File(command).setExecutable(true)
    val nameSeed = scala.concurrent.forkjoin.ThreadLocalRandom.current().nextLong()
    val probe = TestProbe()
    val receiver = system.actorOf(Props(new Receiver(probe.ref, List.empty)), "receiver" + nameSeed)
    val process = system.actorOf(BlockingProcess.props(receiver, List(command)), "process" + nameSeed)

    probe.expectMsg(Receiver.Out("Starting"))

    if (viaDestroy)
      process ! BlockingProcess.Destroy
    else
      system.stop(process)

    probe.expectMsgPF(10.seconds) {
      case BlockingProcess.Exited(value) => value
    } should not be 0

    probe.watch(process)
    probe.expectTerminated(process, 10.seconds)
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
      FlowGraph.closed(Sink.foreach(probe.!)) { implicit b =>
        resultSink =>
          val merge = b.add(Merge[AnyRef](inputPorts = 2))
          Source(stdout).map(element => Out(element.utf8String)) ~> merge.in(0)
          Source(stderr).map(element => Err(element.utf8String)) ~> merge.in(1)
          merge ~> resultSink
      }
        .run()
        .onComplete(_ => self ! "flow-complete")
      Source(stdinInput).map(ByteString.apply).runWith(Sink(stdin))
    case "flow-complete" =>
      unstashAll()
      context become {
        case exited: BlockingProcess.Exited => probe ! exited
      }
    case _ =>
      stash()
  }
}
