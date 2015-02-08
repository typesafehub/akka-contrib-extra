/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

// TODO: SourceInputStream might be needed later, e.g. for a Mesos executor.

package akka.contrib.stream

import akka.actor.{ ActorRef, ActorRefFactory, FSM, Props }
import akka.pattern.ask
import akka.stream.ActorFlowMaterializer
import akka.stream.actor.ActorSubscriberMessage.{ OnComplete, OnError, OnNext }
import akka.stream.actor.{ ActorSubscriber, RequestStrategy, ZeroRequestStrategy }
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.{ ByteString, Timeout }
import java.io.{ IOException, InputStream }
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

private[stream] object ByteStringSubscriber {

  /**
   * Props for the subscriber
   * @param ackTimeout How long to wait for acknowledgements to be received.
   * @return the props subscriber
   */
  def props(ackTimeout: FiniteDuration): Props =
    Props(new ByteStringSubscriber(ackTimeout))

  /**
   * Sent by a client when it is ready to read some bytes. A ByteString will be returned
   * when it is available. A Done will be returned if there are no more bytes.
   * @param max The maximum number of bytes to return. Should be greater than or equal to 0.
   */
  case class Read(max: Int)

  /**
   * After receiving a ByteString an Ack must be returned by the client unless there was
   * an indication that there are no more bytes to be received.
   */
  case object Ack

  /**
   * Sent by the client when no more bytes are required. Sent by the subscriber when no
   * more bytes are available.
   */
  case object Done

  private[stream] sealed trait State

  private[stream] object State {

    case object NothingPending extends State

    case object PendingElement extends State

    case object PendingReceiver extends State

    case object CompletedPendingReceiver extends State

    case object WaitingAck extends State

    case object CompletedWaitingAck extends State

    case object Completed extends State

  }

  private[stream] object Data {
    val empty: Data =
      Data(ByteString.empty, None, 0)
  }

  private[stream] case class Data(bytes: ByteString, receiver: Option[ActorRef], bytesRequired: Int)
}

/**
 * Consumes byte strings providing an api via Read, "Ack the read" and "signal Done". Useful for implementing things like
 * input streams given that the subscriber will hold on to a byte string that has been provided until it is consumed via
 * Read. The next lot of bytes isn't consumed until this subscriber receives an Ack. ByteStrings are also buffered given
 * that the Read operation specifies exactly how many bytes it requires as a maximum.
 */
private[stream] class ByteStringSubscriber(ackTimeout: FiniteDuration)
    extends ActorSubscriber with FSM[ByteStringSubscriber.State, ByteStringSubscriber.Data] {

  import ByteStringSubscriber._

  override protected val requestStrategy: RequestStrategy =
    ZeroRequestStrategy

  startWith(State.NothingPending, Data.empty)

  when(State.NothingPending) {
    case Event(Read(max), Data.empty) =>
      goto(State.PendingElement) using Data(ByteString.empty, Some(sender()), max)

    case Event(OnNext(e: ByteString), Data.empty) =>
      goto(State.PendingReceiver) using Data(e, None, 0)
  }

  when(State.PendingElement) {
    case Event(OnNext(e: ByteString), Data(ByteString.empty, Some(receiver), max)) =>
      val remaining = reply(receiver, e, max)
      goto(State.WaitingAck) using Data(remaining, None, 0)
  }

  when(State.PendingReceiver) {
    case Event(Read(max), Data(bytes, None, 0)) =>
      val remaining = reply(sender(), bytes, max)
      goto(State.WaitingAck) using Data(remaining, None, 0)

    case Event(OnComplete | OnError, _) =>
      goto(State.CompletedPendingReceiver)
  }

  when(State.CompletedPendingReceiver) {
    case Event(Read(max), Data(bytes, None, 0)) =>
      val remaining = reply(sender(), bytes, max)
      goto(State.CompletedWaitingAck) using Data(remaining, None, 0)
  }

  when(State.WaitingAck, stateTimeout = ackTimeout) {
    case Event(Ack, Data(bytes, None, 0)) =>
      if (bytes.isEmpty)
        goto(State.NothingPending)
      else
        goto(State.PendingReceiver)

    case Event(OnComplete | OnError, _) =>
      goto(State.CompletedWaitingAck)

    case Event(StateTimeout, _) =>
      log.error(s"Failed to receive ACK from receiver. Cancelling.")
      cancel()
      goto(State.Completed)
  }

  when(State.CompletedWaitingAck, stateTimeout = ackTimeout) {
    case Event(Ack, Data(bytes, None, 0)) =>
      if (bytes.isEmpty)
        goto(State.Completed)
      else
        goto(State.CompletedPendingReceiver)

    case Event(StateTimeout, _) =>
      log.error(s"Failed to receive ACK from receiver.")
      goto(State.Completed)
  }

  when(State.Completed) {
    case Event(Read(_), Data(_, None, 0)) =>
      sender() ! Done
      stay()

    case _ =>
      stay()
  }

  whenUnhandled {
    case Event(Done, _) =>
      cancel()
      stop()

    case Event(OnNext(e: ByteString), Data(ByteString.empty, _, _)) =>
      stay() using Data(e, None, 0)

    case Event(OnComplete | OnError, Data(_, maybeReceiver, _)) =>
      maybeReceiver foreach (_ ! Done)
      goto(State.Completed)

    case Event(e, s) =>
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      stay()
  }

  initialize()

  override def preStart(): Unit =
    request(1)

  private def reply(receiver: ActorRef, bytes: ByteString, max: Int): ByteString = {
    val (required, remaining) = bytes splitAt max
    receiver ! required
    if (remaining.isEmpty)
      request(1)
    remaining
  }
}

private object SourceInputStream {
  private val eot = -1
}

/**
 * Reads from a source in a blocking manner and in accordance with the JDK InputStream API.
 */
class SourceInputStream(source: Source[ByteString], timeout: FiniteDuration)(implicit factory: ActorRefFactory)
    extends InputStream {

  import SourceInputStream._

  private implicit val materializer = ActorFlowMaterializer()

  private val subscriber = factory.actorOf(ByteStringSubscriber.props(timeout))

  source.runWith(Sink(ActorSubscriber[ByteString](subscriber)))

  override def close(): Unit =
    subscriber ! ByteStringSubscriber.Done

  override final def read(): Int =
    getBytes(1) match {
      case Some(bs) => bs(0) & 0xff
      case None     => eot
    }

  override final def read(bytes: Array[Byte], offset: Int, len: Int): Int =
    getBytes(bytes.size) match {
      case Some(bs) =>
        bs.copyToArray(bytes, offset, bs.size)
        bs.size

      case None =>
        eot
    }

  private def getBytes(size: Int): Option[ByteString] =
    try
      Await.result(subscriber.ask(ByteStringSubscriber.Read(size))(timeout), timeout) match {
        case bytes: ByteString =>
          subscriber ! ByteStringSubscriber.Ack
          Some(bytes)

        case ByteStringSubscriber.Done =>
          None
      }
    catch {
      case e: RuntimeException =>
        throw new IOException("Problem when reading bytes from the sink.", e)
    }
}
