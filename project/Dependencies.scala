import sbt._

object Version {
  val akka                = "2.3.9"
  val akkaStream          = "1.0-M3"
  val mockito             = "1.9.5"
  val scala               = "2.11.5"
  val scalaTest           = "2.2.3"
}

object Library {
  val akkaCluster = "com.typesafe.akka" %% "akka-cluster"             % Version.akka
  val akkaStream  = "com.typesafe.akka" %% "akka-stream-experimental" % Version.akkaStream
  val akkaTestkit = "com.typesafe.akka" %% "akka-testkit"             % Version.akka
  val mockitoAll  = "org.mockito"       %  "mockito-all"              % Version.mockito
  val scalaTest   = "org.scalatest"     %% "scalatest"                % Version.scalaTest
}
