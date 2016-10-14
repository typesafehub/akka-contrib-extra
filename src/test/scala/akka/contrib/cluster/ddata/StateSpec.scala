package akka.contrib.cluster.ddata

import akka.actor.ActorSystem
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait StateSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  protected implicit val testConfig = ConfigFactory.parseString(
    """|akka {
       |  actor {
       |    provider = akka.cluster.ClusterActorRefProvider
       |  }
       |
       |  loggers = ["akka.testkit.TestEventListener"]
       |
       |  loglevel = debug
       |
       |  remote {
       |    enabled-transports          = [akka.remote.netty.tcp]
       |    log-remote-lifecycle-events = off
       |
       |    netty.tcp {
       |      hostname = "127.0.0.1"
       |      port     = 0
       |    }
       |  }
       |
       |  log-dead-letters                 = on
       |  log-dead-letters-during-shutdown = on
       |}
       |""".stripMargin
  )
  protected implicit val system = ActorSystem(this.getClass.getSimpleName, testConfig)
  protected implicit val cluster = Cluster.get(system)

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate(), Duration.Inf)
  }
}
