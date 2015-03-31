import bintray.Plugin.bintrayPublishSettings
import bintray.Keys._
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
    bintrayPublishSettings ++
    List(
      // Core settings
      organization := "com.typesafe.akka",
      scalaVersion := Version.scala,
      crossScalaVersions := List(scalaVersion.value, "2.10.5"),
      scalacOptions ++= List(
        "-unchecked",
        "-deprecation",
        "-language:_",
        "-target:jvm-1.6",
        "-encoding", "UTF-8"
      ),
      unmanagedSourceDirectories in Compile := List((scalaSource in Compile).value),
      unmanagedSourceDirectories in Test := List((scalaSource in Test).value),
      licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
      // Scalariform settings
      ScalariformKeys.preferences := ScalariformKeys.preferences.value
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 100)
        .setPreference(DoubleIndentClassDeclaration, true)
        .setPreference(PreserveDanglingCloseParenthesis, true),
      // Release settings
      ReleaseKeys.crossBuild := true,
      ReleaseKeys.versionBump := sbtrelease.Version.Bump.Minor,
      // Bintray settings
      bintrayOrganization in bintray := Some("akka-contrib-extra")
    )
}
