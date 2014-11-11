import sbt._

object Version {
  val akka                = "2.3.6"
  val akkaDataReplication = "0.7"
  val akkaStream          = "0.10"
  val mockito             = "1.9.5"
  val scala               = "2.11.4"
  val scalaTest           = "2.2.2"
}

object Library {
  val akkaDataReplication = "com.github.patriknw" %% "akka-data-replication"    % Version.akkaDataReplication
  val akkaStream          = "com.typesafe.akka"   %% "akka-stream-experimental" % Version.akkaStream
  val akkaTestkit         = "com.typesafe.akka"   %% "akka-testkit"             % Version.akka
  val mockitoAll          = "org.mockito"         %  "mockito-all"              % Version.mockito
  val scalaTest           = "org.scalatest"       %% "scalatest"                % Version.scalaTest
}
object Resolver {
  val patriknw = "patriknw at bintray" at "http://dl.bintray.com/patriknw/maven"
}
