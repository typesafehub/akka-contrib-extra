/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.process

import akka.actor._
import akka.pattern.ask
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.TestProbe
import akka.testkit._
import akka.util.{ Timeout, ByteString }
import java.io.File
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, DurationInt }

class BlockingProcessSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  implicit val system = ActorSystem("test", testConfig)

  implicit val processCreationTimeout = Timeout(2.seconds.dilated)

  "A BlockingProcess" should {
    "read from stdin and write to stdout" in {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val streamProbe = TestProbe()
      val exitProbe = TestProbe()
      val stdinInput = List("abcd", "1234", "quit")
      val receiver = system.actorOf(Props(new Receiver(streamProbe.ref, exitProbe.ref, command, stdinInput, 1)), "receiver1")
      val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

      val partiallyReceived =
        streamProbe.expectMsgPF() {
          case Receiver.Out("abcd1234") =>
            false
          case Receiver.Out("abcd") =>
            true
        }

      if (partiallyReceived) {
        streamProbe.expectMsg(Receiver.Out("1234"))
      }

      exitProbe.expectMsgPF() {
        case BlockingProcess.Exited(x) => x
      } shouldEqual 0

      exitProbe.watch(process)
      exitProbe.expectTerminated(process)
    }

    "allow a blocking process that is blocked to be destroyed" in {
      expectDestruction(viaDestroy = true)
    }

    "allow a blocking process that is blocked to be stopped" in {
      expectDestruction(viaDestroy = false)
    }

    // FIXME: flaky test: timeout (3 seconds) during expectMsgAnyClassOf waiting for (class akka.actor.Terminated, class akka.contrib.process.BlockingProcess$Exited)
    "be able to create the reference.conf specified limit of processes" ignore {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val probesAndProcesses = for (seed <- 1 to 100) yield {
        val streamProbe = TestProbe()
        val exitProbe = TestProbe()
        val receiver = system.actorOf(Props(new Receiver(streamProbe.ref, exitProbe.ref, command, List.empty, seed)), "receiver-ref-" + seed)
        val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)
        (exitProbe, process)
      }

      probesAndProcesses.foreach {
        case (exitProbe, process) =>
          process ! BlockingProcess.Destroy
          exitProbe.watch(process)
          exitProbe.expectMsgAnyClassOf(classOf[Terminated], classOf[BlockingProcess.Exited])
      }
    }

    "detect when a process has exited while having orphaned children that live on" in {
      val command = getClass.getResource("/loop.sh").getFile
      new File(command).setExecutable(true)
      val nameSeed = scala.concurrent.forkjoin.ThreadLocalRandom.current().nextLong()
      val streamProbe = TestProbe()
      val exitProbe = TestProbe()
      val receiver = system.actorOf(Props(new Receiver(streamProbe.ref, exitProbe.ref, command, List.empty, nameSeed)), "receiver" + nameSeed)
      val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

      exitProbe.watch(process)

      // send loop.sh a sigterm, which it will trap and then sigkill itself, while
      // its child lives on

      streamProbe.expectMsg(Receiver.Out("ready"))

      process ! BlockingProcess.Destroy

      exitProbe.fishForMessage() {
        case BlockingProcess.Exited(r) => true
        case _                         => false
      }
    }
  }

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate(), Duration.Inf)
  }

  def expectDestruction(viaDestroy: Boolean): Unit = {
    val command = getClass.getResource("/sleep.sh").getFile
    new File(command).setExecutable(true)
    val nameSeed = scala.concurrent.forkjoin.ThreadLocalRandom.current().nextLong()
    val streamProbe = TestProbe()
    val exitProbe = TestProbe()
    val receiver = system.actorOf(Props(new Receiver(streamProbe.ref, exitProbe.ref, command, List.empty, nameSeed)), "receiver" + nameSeed)
    val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

    streamProbe.expectMsg(Receiver.Out("Starting"))

    if (viaDestroy)
      process ! BlockingProcess.Destroy
    else
      system.stop(process)

    exitProbe.expectMsgPF(10.seconds) {
      case BlockingProcess.Exited(v) => v
    } should not be 0

    exitProbe.watch(process)
    exitProbe.expectTerminated(process, 10.seconds)
  }
}

object Receiver {
  case object Process
  case class Out(s: String)
  case class Err(s: String)
}

class Receiver(streamProbe: ActorRef, exitProbe: ActorRef, command: String, stdinInput: immutable.Seq[String], nameSeed: Long) extends Actor
    with Stash {

  final implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val process = context.actorOf(BlockingProcess.props(List(command)), "process" + nameSeed)
  import Receiver._
  import context.dispatcher

  override def receive: Receive = {
    case Process =>
      sender() ! process
    case BlockingProcess.Started(stdin, stdout, stderr) =>
      stdout
        .map(element => Out(element.utf8String))
        .merge(stderr.map(element => Err(element.utf8String)))
        .runWith(Sink.foreach(streamProbe.tell(_, Actor.noSender)))
        .onComplete(_ => self ! "flow-complete")

      Source(stdinInput).map(ByteString.apply).runWith(stdin)
    case exited: BlockingProcess.Exited =>
      exitProbe ! exited
    case "flow-complete" =>
      unstashAll()
      context become {
        case exited: BlockingProcess.Exited => exitProbe ! exited
      }
    case _ =>
      stash()
  }
}
