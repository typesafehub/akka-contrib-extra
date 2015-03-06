/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.stream

import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import java.io.InputStream
import java.nio.charset.Charset
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.concurrent.duration.DurationInt

class SourceInputStreamSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  "A SourceInputStream" should {
    "return -1 when there are no more elements" in {
      withSourceInputStream(List.empty) { in =>
        in.read() should be(-1)
      }
    }

    "return 'abc' and then -1 given a source with only one element 'abc'" in {
      withSourceInputStream(List("abc")) { in =>
        val buf = Array.ofDim[Byte](3)
        in.read(buf) should be(3)
        new String(buf, Utf8) should be("abc")

        in.read() should be(-1)
      }
    }

    "return 'a' then 'bc' and then -1 given a source with elements 'a' and 'bc'" in {
      withSourceInputStream(List("a", "bc")) { in =>
        in.read() should be('a')

        val buf = Array.ofDim[Byte](2)
        in.read(buf) should be(2)
        new String(buf, Utf8) should be("bc")

        in.read() should be(-1)
      }
    }
  }

  override protected def afterAll(): Unit = {
    system.shutdown()
    system.awaitTermination()
  }

  private implicit val system = ActorSystem("test", testConfig)

  private implicit val materializer = ActorFlowMaterializer()

  private val Utf8 = Charset.forName("UTF-8")

  private val timeout = 5.seconds

  private def withSourceInputStream[T](tests: List[String], bufferSize: Int = 10)(action: InputStream => T): T = {
    val source = Source(tests.map(s => ByteString(s)))
    val in = new SourceInputStream(source, timeout)
    try
      action(in)
    finally
      in.close()
  }
}
