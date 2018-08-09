import sbt._

object Version {
  val akka       = "2.5.14"
  val akkaHttp   = "10.1.3"
  val mockito    = "1.10.19"
  val nuprocess  = "1.2.4"
  val scala      = "2.11.12"
  val scalaTest  = "3.0.5"
}

object Library {
  val akkaCluster           = "com.typesafe.akka" %% "akka-cluster"          % Version.akka
  val akkaHttp              = "com.typesafe.akka" %% "akka-http"             % Version.akkaHttp
  val akkaStream            = "com.typesafe.akka" %% "akka-stream"           % Version.akka
  val akkaDistributedData   = "com.typesafe.akka" %% "akka-distributed-data" % Version.akka
  val akkaTestkit           = "com.typesafe.akka" %% "akka-testkit"          % Version.akka
  val akkaHttpTestkit       = "com.typesafe.akka" %% "akka-http-testkit"     % Version.akkaHttp
  val mockitoAll            = "org.mockito"       %  "mockito-all"           % Version.mockito
  val nuprocess             = "com.zaxxer"        %  "nuprocess"             % Version.nuprocess
  val scalaTest             = "org.scalatest"     %% "scalatest"             % Version.scalaTest
}
