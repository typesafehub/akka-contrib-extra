/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.contrib.process

import akka.actor.{ Actor, ActorLogging, NoSerializationVerificationNeeded, Props }
import akka.stream.scaladsl.{ BroadcastHub, FileIO, Keep, Sink, Source, SourceQueueWithComplete }
import akka.util.ByteString
import java.nio.ByteBuffer
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.TimeUnit

import akka.stream._
import akka.{ Done, NotUsed }
import com.zaxxer.nuprocess.{ NuAbstractProcessHandler, NuProcess, NuProcessBuilder }

import scala.collection.JavaConverters
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

object NonBlockingProcess {
  /**
   * Configuration for NuProcess. The users should be in charge of resource management,
   * so the shutdown hook is disabled. Additionally, since this is async I/O, 1 thread
   * should be plenty to deal with any I/O events.
   */
  System.setProperty("com.zaxxer.nuprocess.enableShutdownHook", "false")
  System.setProperty("com.zaxxer.nuprocess.threads", "1")

  /**
   * Sent to the receiver on startup - specifies the streams used for managing input, output and error respectively.
   * This message should only be received by the parent of the NonBlockingProcess and should not be passed across the
   * JVM boundary (the publishers are not serializable).
   *
   * @param pid    the process id
   * @param stdin  a `akka.stream.scaladsl.Sink[ByteString]` for the standard input stream of the process
   * @param stdout a `akka.stream.scaladsl.Source[ByteString]` for the standard output stream of the process
   * @param stderr a `akka.stream.scaladsl.Source[ByteString]` for the standard error stream of the process
   */
  case class Started(
    pid: Long,
    stdin: Sink[ByteString, Future[Done]],
    stdout: Source[ByteString, NotUsed],
    stderr: Source[ByteString, NotUsed]) extends NoSerializationVerificationNeeded

  /**
   * Sent to the receiver after the process has exited.
   *
   * @param exitValue the exit value of the process
   */
  case class Exited(exitValue: Int)

  /**
   * Send a request to destroy the process.
   * On POSIX, this sends a SIGTERM, but implementation is platform specific.
   */
  case object Destroy

  /**
   * Send a request to forcibly destroy the process.
   * On POSIX, this sends a SIGKILL, but implementation is platform specific.
   */
  case object DestroyForcibly

  /**
   * Create Props for a [[NonBlockingProcess]] actor.
   *
   * @param command     signifies the program to be executed and its optional arguments
   * @param workingDir  the working directory for the process; default is the current working directory
   * @param environment the environment for the process; default is `Map.emtpy`
   * @return Props for a [[NonBlockingProcess]] actor
   */
  def props(
    command: immutable.Seq[String],
    workingDir: Path = Paths.get(System.getProperty("user.dir")),
    environment: Map[String, String] = Map.empty) =
    Props(new NonBlockingProcess(command, workingDir, environment))

  // For additional process detection for platforms that support "/proc"
  private[process] val procDir = Paths.get("/proc")
  private[process] val hasProcDir = Files.exists(procDir)

  // For additional checking on whether a process is alive
  private[process] case object Inspect

  /**
   * Determines if a process is still alive according to its entry in /proc/{pid}/stat. A process is considered
   * dead if there is no stat file, or if its "State" is "Z" (zombie)
   *
   * More info: http://man7.org/linux/man-pages/man5/proc.5.html
   */
  private def isProcDirAlive(pid: Long)(implicit mat: ActorMaterializer, ec: ExecutionContext) =
    FileIO.fromPath(procDir.resolve(pid.toString).resolve("stat"))
      .runFold("")(_ + _.utf8String)
      .map { fileData =>
        val fields = fileData.split(' ')
        val state = if (fields.length > 2) fields(2) else ""

        state.nonEmpty && state != "Z"
      }
      .recover {
        case _ => false
      }

  private[process] def enqueue(queue: SourceQueueWithComplete[ByteString], buffer: ByteBuffer, maxBytes: Int, closed: Boolean): Unit = {
    while (buffer.hasRemaining) {
      val len = Math.min(buffer.remaining(), maxBytes)
      val arr = new Array[Byte](len)
      buffer.get(arr, 0, len)
      val byteString = ByteString.ByteString1C(arr)
      queue.offer(byteString)
    }
    if (closed) queue.complete()
  }
}

/**
 * NonBlockingProcess encapsulates an operating system process and its ability to be communicated with via stdio i.e.
 * stdin, stdout and stderr. The reactive streams for stdio are communicated in a NonBlockingProcess.Started event
 * upon the actor being established. The parent actor is then subsequently streamed
 * stdout and stderr events. When the process exists (determined by periodically polling process.isAlive()) then
 * the process's exit code is communicated to the receiver in a NonBlockingProcess.Exited event.
 *
 * All IO is performed in a non-blocking manner. Herein lies the difference between [[BlockingProcess]] and this
 * actor. As such, many more processes can be managed via this while consuming *much* less memory on your JVM.
 * Always favour this actor over its blocking counterpart.
 */
class NonBlockingProcess(
  command: immutable.Seq[String],
  directory: Path,
  environment: Map[String, String])
    extends Actor with ActorLogging {

  import NonBlockingProcess._
  import context.dispatcher

  private val stdoutMaxBytesPerChunk =
    context.system.settings.config.getInt("akka.process.non-blocking-process.stdout-max-bytes-per-chunk")
  private val stdoutBufferMaxChunks =
    context.system.settings.config.getInt("akka.process.non-blocking-process.stdout-buffer-max-chunks")
  private val stderrMaxBytesPerChunk =
    context.system.settings.config.getInt("akka.process.non-blocking-process.stderr-max-bytes-per-chunk")
  private val stderrBufferMaxChunks =
    context.system.settings.config.getInt("akka.process.non-blocking-process.stderr-buffer-max-chunks")

  private val inspectionInterval =
    Duration(
      context.system.settings.config.getDuration("akka.process.non-blocking-process.inspection-interval").toMillis,
      TimeUnit.MILLISECONDS
    )

  private val inspectionTick =
    context.system.scheduler.schedule(inspectionInterval, inspectionInterval, self, Inspect)

  private val contextMat = ActorMaterializer()
  private val systemMat = ActorMaterializer()(context.system)

  val process: NuProcess = {
    import JavaConverters._

    val pb = new NuProcessBuilder(command.asJava)

    pb.environment().putAll(environment.asJava)

    pb.setCwd(directory)

    pb.setProcessListener(new NuAbstractProcessHandler {
      override def onPreStart(nuProcess: NuProcess): Unit = {
        // Create our stream based actors away from this one given that we want them to continue
        // for a small while post the actor dying (it may take a tiny bit longer for the process
        // to terminate).
        implicit val stdioMat: ActorMaterializer = systemMat
        val stdin =
          Sink
            .foreach[ByteString](bytes => nuProcess.writeStdin(bytes.toByteBuffer))
        val (out, stdout) =
          Source
            .queue[ByteString](stdoutBufferMaxChunks, OverflowStrategy.dropHead)
            .toMat(BroadcastHub.sink)(Keep.both)
            .run
        val (err, stderr) =
          Source
            .queue[ByteString](stderrBufferMaxChunks, OverflowStrategy.dropHead)
            .toMat(BroadcastHub.sink)(Keep.both)
            .run

        nuProcess.setProcessHandler(new NuAbstractProcessHandler {
          override def onStart(nuProcess: NuProcess): Unit =
            self ! Started(nuProcess.getPID, stdin, stdout, stderr)

          override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit =
            enqueue(err, buffer, stderrMaxBytesPerChunk, closed)

          override def onExit(exitCode: Int): Unit =
            self ! Exited(exitCode)

          override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit =
            enqueue(out, buffer, stdoutMaxBytesPerChunk, closed)
        })
      }
    })

    log.debug("Process starting: {}", command.headOption.getOrElse("<unknown>"))
    pb.start()
  }

  override def receive: Receive = {
    case s: Started =>
      context.parent ! s
    case e: Exited =>
      context.parent ! e
      context.stop(self)
    case Destroy =>
      log.debug("Received request to destroy the process.")
      process.destroy(false)
    case DestroyForcibly =>
      log.debug("Received request to forcibly destroy the process.")
      process.destroy(true)
    case Inspect =>
      if (hasProcDir && process.isRunning)
        isProcDirAlive(process.getPID)(contextMat, implicitly).filter(_ == false).foreach { _ =>
          log.debug("Process has terminated, killing self")
          self ! Exited(255)
        }
  }

  override def postStop(): Unit = {
    inspectionTick.cancel()
    process.destroy(true)
    contextMat.shutdown()
    systemMat.shutdown()
  }
}
