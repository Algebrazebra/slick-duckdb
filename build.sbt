import _root_.io.github.nafg.mergify.dsl.*

organization         := "io.github.algebrazebra"
name                 := "slick-duckdb"
version              := "0.1.0"
versionScheme        := Some("early-semver")
homepage             := Some(url("https://github.com/algebrazebra/slick-duckdb"))
scmInfo              := Some(
  ScmInfo(
    url("https://github.com/algebrazebra/slick-duckdb"),
    "scm:git:git@github.com:algebrazebra/slick-duckdb.git"
  )
)
publishTo            := sonatypePublishToBundle.value
pomIncludeRepository := { _ => false }
description          := "Slick database profile for DuckDB"

developers := List(
  Developer(
    "algebrazebra",
    "algebrazebra",
    "algebrazebra@users.noreply.github.com",
    url("https://github.com/algebrazebra")
  )
)

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / scalacOptions += "-Xsource:3"

mergifyExtraConditions := Seq(
  (Attr.Author :== "scala-steward") ||
    (Attr.Author :== "slick-scala-steward[bot]") ||
    (Attr.Author :== "renovate[bot]")
)

libraryDependencies ++= List(
  "org.duckdb"     % "duckdb_jdbc"     % "1.3.2.0",
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
  "ch.qos.logback" % "logback-classic" % "1.5.27" % Test,
  "org.scalatest" %% "scalatest"       % "3.2.19" % Test
)

scalacOptions += "-deprecation"

Test / parallelExecution := false

logBuffered := false

run / fork := true

testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a")
libraryDependencies += "com.typesafe.slick" %% "slick-testkit" % "3.6.1"
libraryDependencies += "org.scala-lang"      % "scala-reflect" % scalaVersion.value

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))

licenses := List(
  "AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.en.html")
)
