import sbt._

object Version {
  val akka       = "2.4.7"
  val mockito    = "1.9.5"
  val scala      = "2.11.8"
  val scalaTest  = "2.2.6"
}

object Library {
  val akkaCluster     = "com.typesafe.akka" %% "akka-cluster"           % Version.akka
  val akkaHttp        = "com.typesafe.akka" %% "akka-http-experimental" % Version.akka
  val akkaStream      = "com.typesafe.akka" %% "akka-stream"            % Version.akka
  val akkaTestkit     = "com.typesafe.akka" %% "akka-testkit"           % Version.akka
  val akkaHttpTestkit = "com.typesafe.akka" %% "akka-http-testkit"      % Version.akka
  val mockitoAll      = "org.mockito"       %  "mockito-all"            % Version.mockito
  val scalaTest       = "org.scalatest"     %% "scalatest"              % Version.scalaTest
}
