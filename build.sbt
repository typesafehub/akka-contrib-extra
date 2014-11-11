lazy val akkaContribExtra = project.in(file("."))

name := "akka-contrib-extra"

libraryDependencies ++= List(
)

initialCommands := """|import com.typesafe.akka.akkacontribextra._""".stripMargin
