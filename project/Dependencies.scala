import sbt._

object Version {
  val akka       = "2.3.12"
  val akkaHttp   = "2.0.1"
  val akkaStream = "2.0.1"
  val mockito    = "1.9.5"
  val scala      = "2.11.7"
  val scalaTest  = "2.2.4"
}

object Library {
  val akkaCluster     = "com.typesafe.akka" %% "akka-cluster"                   % Version.akka
  val akkaHttp        = "com.typesafe.akka" %% "akka-http-experimental"         % Version.akkaHttp
  val akkaStream      = "com.typesafe.akka" %% "akka-stream-experimental"       % Version.akkaStream
  val akkaTestkit     = "com.typesafe.akka" %% "akka-testkit"                   % Version.akka
  val akkaHttpTestkit = "com.typesafe.akka" %% "akka-http-testkit-experimental" % Version.akkaStream
  val mockitoAll      = "org.mockito"       %  "mockito-all"                    % Version.mockito
  val scalaTest       = "org.scalatest"     %% "scalatest"                      % Version.scalaTest
}
