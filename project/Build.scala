import com.typesafe.sbt.SbtScalariform._
import sbt._
import sbt.Keys._
import sbtrelease.ReleasePlugin._
import scalariform.formatter.preferences._

object Build extends AutoPlugin {

  override def requires =
    plugins.JvmPlugin

  override def trigger =
    allRequirements

  override def projectSettings =
    scalariformSettings ++
    releaseSettings ++
    List(
      // Core settings
      organization := "com.typesafe.akka",
      scalaVersion := Version.scala,
      crossScalaVersions := List(scalaVersion.value, "2.10.4"),
      scalacOptions ++= List(
        "-unchecked",
        "-deprecation",
        "-language:_",
        "-target:jvm-1.7",
        "-encoding", "UTF-8"
      ),
      unmanagedSourceDirectories in Compile := List((scalaSource in Compile).value),
      unmanagedSourceDirectories in Test := List((scalaSource in Test).value),
      publishTo := {
        val typesafe = "http://private-repo.typesafe.com/typesafe"
        val (name, u) =
          if (isSnapshot.value)
            "typesafe-snapshots" -> url(s"$typesafe/maven-snapshots/")
          else
            "typesafe-releases" -> url(s"$typesafe/maven-releases/")
        Some(sbt.Resolver.url(name, u))
      },
      ReleaseKeys.crossBuild := true,
      // Scalariform settings
      ScalariformKeys.preferences := ScalariformKeys.preferences.value
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 100)
        .setPreference(DoubleIndentClassDeclaration, true)
        .setPreference(PreserveDanglingCloseParenthesis, true),
      // Release settings
      ReleaseKeys.versionBump := sbtrelease.Version.Bump.Minor
    )
}
