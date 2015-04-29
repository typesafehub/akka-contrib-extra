/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.process

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, Terminated }
import akka.contrib.stream.{ InputStreamPublisher, OutputStreamSubscriber }
import akka.stream.actor.{ ActorPublisher, ActorSubscriber }
import akka.util.{ ByteString, Helpers }
import java.io.File
import java.lang.{ Process => JavaProcess, ProcessBuilder => JavaProcessBuilder }
import org.reactivestreams.{ Publisher, Subscriber }
import scala.collection.JavaConverters
import scala.collection.immutable
import scala.concurrent.blocking
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

object BlockingProcess {

  /**
   * Sent to the receiver on startup - specifies the streams used for managing input, output and error respectively.
   * @param stdin a `org.reactivestreams.Subscriber` for the standard input stream of the process
   * @param stdout a `org.reactivestreams.Publisher` for the standard output stream of the process
   * @param stderr a `org.reactivestreams.Publisher` for the standard error stream of the process
   */
  case class Started(stdin: Subscriber[ByteString], stdout: Publisher[ByteString], stderr: Publisher[ByteString])

  /**
   * Sent to the receiver after the process has exited.
   * @param exitValue the exit value of the process
   */
  case class Exited(exitValue: Int)

  /**
   * Terminate the associated process immediately. This will cause this actor to stop.
   */
  case object Destroy

  /**
   * Create Props for a [[BlockingProcess]] actor.
   * @param receiver the actor to receive output and error events
   * @param command signifies the program to be executed and its optional arguments
   * @param workingDir the working directory for the process; default is the current working directory
   * @param environment the environment for the process; default is `Map.emtpy`
   * @param stdioTimeout the amount of time to tolerate waiting for a process to communicate back to this actor
   * @return Props for a [[BlockingProcess]] actor
   */
  def props(
    receiver: ActorRef,
    command: immutable.Seq[String],
    workingDir: File = new File(System.getProperty("user.dir")),
    environment: Map[String, String] = Map.empty,
    stdioTimeout: Duration = Duration.Undefined) =
    Props(new BlockingProcess(receiver, command, workingDir, environment, stdioTimeout))

  /**
   * This quoting functionality is as recommended per http://bugs.java.com/view_bug.do?bug_id=6511002
   * The JDK can't change due to its backward compatibility requirements, but we have no such constraint
   * here. Args should be able to be expressed consistently by the user of our API no matter whether
   * execution is on Windows or not.
   *
   * @param s command string to be quoted
   * @return quoted string
   */
  private def winQuote(s: String): String = {
    def needsQuoting(s: String) =
      s.isEmpty || (s exists (c => c == ' ' || c == '\t' || c == '\\' || c == '"'))
    if (needsQuoting(s)) {
      val quoted = s.replaceAll("""([\\]*)"""", """$1$1\\"""").replaceAll("""([\\]*)\z""", "$1$1")
      s""""$quoted""""
    } else
      s
  }
}

/**
 * BlockingProcess encapsulates an operating system process and its ability to be communicated with via stdio i.e.
 * stdin, stdout and stderr. The reactive streams for stdio are communicated in a BlockingProcess.Started event
 * upon the actor being established. The receiving actor, passed in as a constructor arg, is then subsequently streamed
 * stdout and stderr events. When there are no more stdout or stderr events then the process's exit code is
 * communicated to the receiver in a BlockingProcess.Exited event unless the process is a detached one.
 *
 * The actor should be associated with a dedicated dispatcher as various `java.io` calls are made which can block.
 */
class BlockingProcess(
  receiver: ActorRef,
  command: immutable.Seq[String],
  directory: File,
  environment: Map[String, String],
  stdioTimeout: Duration)
    extends Actor with ActorLogging {

  import BlockingProcess._

  override val supervisorStrategy: SupervisorStrategy =
    SupervisorStrategy.stoppingStrategy

  override def preStart(): Unit = {
    val process: JavaProcess = {
      import JavaConverters._
      val preparedCommand = if (Helpers.isWindows) command map winQuote else command
      val pb = new JavaProcessBuilder(preparedCommand.asJava)
      pb.environment().putAll(environment.asJava)
      pb.directory(directory)
      pb.start()
    }

    try {
      val stdin =
        context.actorOf(OutputStreamSubscriber.props(process.getOutputStream), "stdin")

      val stdout =
        context.watch(context.actorOf(InputStreamPublisher.props(process.getInputStream, stdioTimeout), "stdout"))

      val stderr =
        context.watch(context.actorOf(InputStreamPublisher.props(process.getErrorStream, stdioTimeout), "stderr"))

      receiver ! Started(ActorSubscriber(stdin), ActorPublisher(stdout), ActorPublisher(stderr))
    } finally {
      val destroyer =
        context.watch(context.actorOf(ProcessDestroyer.props(process, receiver), "process-destroyer"))
    }
  }

  override def receive = {
    case Destroy =>
      log.debug("Received request to destroy the process.")
      context.stop(self)
    case Terminated(ref) =>
      log.debug("Child {} was unexpectedly stopped, shutting down process", ref.path)
      context.stop(self)
  }
}

private object ProcessDestroyer {
  def props(process: JavaProcess, exitValueReceiver: ActorRef): Props =
    Props(new ProcessDestroyer(process, exitValueReceiver))
}

private class ProcessDestroyer(process: JavaProcess, exitValueReceiver: ActorRef) extends Actor {
  override def receive =
    Actor.emptyBehavior

  override def postStop(): Unit = {
    val exitValue = blocking {
      process.destroy()
      process.waitFor()
    }
    exitValueReceiver ! BlockingProcess.Exited(exitValue)
  }
}