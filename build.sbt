lazy val akkaContribExtra = project in file(".")

name := "akka-contrib-extra"

libraryDependencies ++= List(
  Library.akkaCluster,
  Library.akkaStream,
  Library.akkaHttp,
  Library.akkaDistributedData,
  Library.nuprocess,
  Library.akkaTestkit     % "test",
  Library.akkaHttpTestkit % "test",
  Library.mockitoAll      % "test",
  Library.scalaTest       % "test"
)
