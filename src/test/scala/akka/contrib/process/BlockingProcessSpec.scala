/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.process

import akka.actor._
import akka.pattern.ask
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.TestProbe
import akka.util.{ Timeout, ByteString }
import java.io.File
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, DurationInt }

class BlockingProcessSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  implicit val system = ActorSystem("test", testConfig)

  implicit val processCreationTimeout = Timeout(2.seconds)

  "A BlockingProcess" should {
    "read from stdin and write to stdout" in {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val probe = TestProbe()
      val stdinInput = List("abcd", "1234", "quit")
      val receiver = system.actorOf(Props(new Receiver(probe.ref, command, stdinInput, 1)), "receiver1")
      val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

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

    "be able to create the reference.conf specified limit of processes" in {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val probesAndProcesses = for (seed <- 1 to 100) yield {
        val probe = TestProbe()
        val receiver = system.actorOf(Props(new Receiver(probe.ref, command, List.empty, seed)), "receiver-ref-" + seed)
        val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)
        (probe, process)
      }

      probesAndProcesses.foreach {
        case (probe, process) =>
          process ! BlockingProcess.Destroy
          probe.watch(process)
          probe.expectMsgAnyClassOf(classOf[Terminated], classOf[BlockingProcess.Exited])
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
    val probe = TestProbe()
    val receiver = system.actorOf(Props(new Receiver(probe.ref, command, List.empty, nameSeed)), "receiver" + nameSeed)
    val process = Await.result(receiver.ask(Receiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

    probe.expectMsg(Receiver.Out("Starting"))

    if (viaDestroy)
      process ! BlockingProcess.Destroy
    else
      system.stop(process)

    probe.expectMsgPF(10.seconds) {
      case BlockingProcess.Exited(v) => v
    } should not be 0

    probe.watch(process)
    probe.expectTerminated(process, 10.seconds)
  }
}

object Receiver {
  case object Process
  case class Out(s: String)
  case class Err(s: String)
}

class Receiver(probe: ActorRef, command: String, stdinInput: immutable.Seq[String], nameSeed: Long) extends Actor
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
        .runWith(Sink.foreach(probe.tell(_, Actor.noSender)))
        .onComplete(_ => self ! "flow-complete")

      Source(stdinInput).map(ByteString.apply).runWith(stdin)
    case "flow-complete" =>
      unstashAll()
      context become {
        case exited: BlockingProcess.Exited => probe ! exited
      }
    case _ =>
      stash()
  }
}
