/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.process

import akka.actor.{ Actor, ActorLogging, ActorRef, NoSerializationVerificationNeeded, Props, SupervisorStrategy, Terminated }
import akka.stream.{ IOResult, ActorAttributes }
import akka.stream.scaladsl.{ StreamConverters, Sink, Source }
import akka.util.{ ByteString, Helpers }
import java.io.File
import java.lang.{ Process => JavaProcess, ProcessBuilder => JavaProcessBuilder }
import scala.collection.JavaConverters
import scala.collection.immutable
import scala.concurrent.{ Future, blocking }
import scala.concurrent.duration.Duration

object BlockingProcess {

  /**
   * The configuration key to use in order to override the dispatcher used for blocking IO.
   */
  final val BlockingIODispatcherId = "akka.process.blocking-process.blocking-io-dispatcher-id"

  /**
   * Sent to the receiver on startup - specifies the streams used for managing input, output and error respectively.
   * This message should only be received by the parent of the BlockingProcess and should not be passed across the
   * JVM boundary (the publishers are not serializable).
   *
   * @param stdin a `akka.stream.scaladsl.Sink[ByteString, Future[IOResult]]` for the standard input stream of the process
   * @param stdout a `akka.stream.scaladsl.Source[ByteString, Future[IOResult]]` for the standard output stream of the process
   * @param stderr a `akka.stream.scaladsl.Source[ByteString, Future[IOResult]]` for the standard error stream of the process
   */
  case class Started(stdin: Sink[ByteString, Future[IOResult]], stdout: Source[ByteString, Future[IOResult]], stderr: Source[ByteString, Future[IOResult]])
    extends NoSerializationVerificationNeeded

  /**
   * Sent to the receiver after the process has exited.
   *
   * @param exitValue the exit value of the process
   */
  case class Exited(exitValue: Int)

  /**
   * Terminate the associated process immediately. This will cause this actor to stop.
   */
  case object Destroy

  /**
   * Sent if stdin from the process is terminated
   */
  case object StdinTerminated

  /**
   * Sent if stdout from the process is terminated
   */
  case object StdoutTerminated

  /**
   * Sent if stderr from the process is terminated
   */
  case object StderrTerminated

  /**
   * Create Props for a [[BlockingProcess]] actor.
   *
   * @param command signifies the program to be executed and its optional arguments
   * @param workingDir the working directory for the process; default is the current working directory
   * @param environment the environment for the process; default is `Map.emtpy`
   * @param stdioTimeout the amount of time to tolerate waiting for a process to communicate back to this actor
   * @return Props for a [[BlockingProcess]] actor
   */
  def props(
    command: immutable.Seq[String],
    workingDir: File = new File(System.getProperty("user.dir")),
    environment: Map[String, String] = Map.empty,
    stdioTimeout: Duration = Duration.Undefined) =
    Props(new BlockingProcess(command, workingDir, environment, stdioTimeout))

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
 * upon the actor being established. The parent actor is then subsequently streamed
 * stdout and stderr events. When there are no more stdout or stderr events then the process's exit code is
 * communicated to the receiver in a BlockingProcess.Exited event unless the process is a detached one.
 *
 * A dispatcher as indicated by the "akka.process.blocking-process.blocking-io-dispatcher-id" setting is used
 * internally by the actor as various JDK calls are made which can block.
 */
class BlockingProcess(
  command: immutable.Seq[String],
  directory: File,
  environment: Map[String, String],
  stdioTimeout: Duration)
    extends Actor with ActorLogging {

  import BlockingProcess._
  import context.dispatcher

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

    val blockingIODispatcherId = context.system.settings.config.getString(BlockingIODispatcherId)

    try {
      val selfRef = context.self
      val selfDispatcherAttribute = ActorAttributes.dispatcher(blockingIODispatcherId)

      val stdin = StreamConverters.fromOutputStream(process.getOutputStream)
        .withAttributes(selfDispatcherAttribute)
        .mapMaterializedValue(_.andThen { case _ => selfRef ! StdinTerminated })

      val stdout = StreamConverters.fromInputStream(process.getInputStream)
        .withAttributes(selfDispatcherAttribute)
        .mapMaterializedValue(_.andThen { case _ => selfRef ! StdoutTerminated })

      val stderr = StreamConverters.fromInputStream(process.getErrorStream)
        .withAttributes(selfDispatcherAttribute)
        .mapMaterializedValue(_.andThen { case _ => selfRef ! StderrTerminated })

      context.parent ! Started(stdin, stdout, stderr)

      log.debug(s"Blocking process started with dispatcher: $blockingIODispatcherId")

    } finally {
      context.watch(context.actorOf(
        ProcessDestroyer.props(process, context.parent).withDispatcher(blockingIODispatcherId),
        "process-destroyer"
      ))
    }
  }

  override def receive = handleMessages(stdOutTerminated = false, stdErrTerminated = false)

  private def handleMessages(stdOutTerminated: Boolean, stdErrTerminated: Boolean): Receive = {
    case Destroy =>
      log.debug("Received request to destroy the process.")
      context.stop(self)
    case Terminated(ref) =>
      log.debug("Child {} was unexpectedly stopped, shutting down process", ref.path)
      context.stop(self)
    case StdinTerminated =>
      log.debug("Stdin was terminated")
    case StdoutTerminated =>
      if (stdErrTerminated) {
        log.debug("Stdout and Stderr was terminated, shutting down process")
        context.stop(self)
      } else {
        log.debug("Stdout was terminated")
        context.become(handleMessages(stdOutTerminated = true, stdErrTerminated))
      }
    case StderrTerminated =>
      if (stdOutTerminated) {
        log.debug("Stdout and Stderr was terminated, shutting down process")
        context.stop(self)
      } else {
        log.debug("Stderr was terminated")
        context.become(handleMessages(stdOutTerminated, stdErrTerminated = true))
      }
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