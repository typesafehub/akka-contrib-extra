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
import java.nio.file.Path
import org.reactivestreams.{ Publisher, Subscriber }
import scala.collection.JavaConverters
import scala.collection.immutable
import scala.concurrent.blocking
import scala.concurrent.duration.Duration

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
   * @param isDetached whether the process will be daemonic; default is `false`
   * @param stdioTimeout the amount of time to tolerate waiting for a process to communicate back to this actor
   * @return Props for a [[BlockingProcess]] actor
   */
  def props(
    receiver: ActorRef,
    command: immutable.Seq[String],
    workingDir: Path = new File(System.getProperty("user.dir")).toPath,
    environment: Map[String, String] = Map.empty,
    isDetached: Boolean = false,
    stdioTimeout: Duration = Duration.Undefined) =
    Props(new BlockingProcess(receiver, command, workingDir, environment, isDetached, stdioTimeout))

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
 * stdin, stdout and stderr. The reactive streams for stdio are communicated in a [[BlockingProcess.Started]] event
 * upon the actor being established. The receiving actor, passed in as a constructor arg, is then subsequently streamed
 * stdout and stderr events. When there are no more stdout or stderr events then the process's exit code is
 * communicated to the receiver in a [[BlockingProcess.Exited]] event unless the process is a detached one.
 *
 * The actor should be associated with a dedicated dispatcher as various `java.io` calls are made which can block.
 */
class BlockingProcess(
  receiver: ActorRef,
  command: immutable.Seq[String],
  directory: Path,
  environment: Map[String, String],
  isDetached: Boolean,
  stdioTimeout: Duration)
    extends Actor with ActorLogging {

  import BlockingProcess._

  override val supervisorStrategy: SupervisorStrategy =
    SupervisorStrategy.stoppingStrategy

  private val process = startProcess()

  private val stdin =
    context.actorOf(OutputStreamSubscriber.props(process.getOutputStream), "stdin")

  private val stdout =
    context.watch(context.actorOf(InputStreamPublisher.props(process.getInputStream, stdioTimeout), "stdout"))

  private val stderr =
    context.watch(context.actorOf(InputStreamPublisher.props(process.getErrorStream, stdioTimeout), "stderr"))

  private var nrOfPublishers = 2

  override def preStart(): Unit =
    receiver ! Started(ActorSubscriber(stdin), ActorPublisher(stdout), ActorPublisher(stderr))

  override def receive = {
    case Destroy =>
      log.debug("Received request to destroy the process.")
      process.destroy()

    case Terminated(publisher @ (`stdout` | `stderr`)) =>
      log.debug("Publisher {} has terminated", publisher.path.name)
      nrOfPublishers -= 1
      if (nrOfPublishers == 0 && !isDetached) {
        val exitValue =
          blocking {
            process.waitFor()
            process.exitValue()
          }
        receiver ! Exited(exitValue)
        context.stop(self)
      }
  }

  override def postStop(): Unit =
    if (!isDetached) process.destroy()

  private def startProcess(): JavaProcess = {
    import JavaConverters._
    val pb = new JavaProcessBuilder(prepareCommand(command).asJava)
    pb.environment().putAll(environment.asJava)
    pb.directory(directory.toFile)
    pb.start()
  }

  private def prepareCommand(args: immutable.Seq[String]): immutable.Seq[String] =
    if (Helpers.isWindows)
      args map winQuote
    else
      args
}
