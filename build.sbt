organization         := "io.github.algebrazebra"
name                 := "slick-duckdb"
version              := "0.0.1"
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
ThisBuild / crossScalaVersions := Seq("2.12.20", "2.13.16")
ThisBuild / scalacOptions += "-Xsource:3"
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))

val duckdbVersions = Seq("1.3.2.0", "1.3.1", "1.3.0")
ThisBuild / githubWorkflowBuildMatrixAdditions += "duckdb" -> duckdbVersions
ThisBuild / githubWorkflowBuildSbtStepPreamble += s"set libraryDependencies := libraryDependencies.value.map { case m if m.organization == \"org.duckdb\" && m.name == \"duckdb_jdbc\" => m.withRevision($${{ matrix.duckdb }}) case m => m }"

libraryDependencies ++= List(
  "org.duckdb"          % "duckdb_jdbc"     % "1.3.2.0",
  "com.github.sbt"      % "junit-interface" % "0.13.3" % Test,
  "ch.qos.logback"      % "logback-classic" % "1.5.27" % Test,
  "org.scalatest"      %% "scalatest"       % "3.2.19" % Test,
  "com.typesafe.slick" %% "slick-testkit"   % "3.6.1",
  "org.scala-lang"      % "scala-reflect"   % scalaVersion.value
)

scalacOptions += "-deprecation"

Test / parallelExecution := false

logBuffered := false

run / fork := true

testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a")

licenses := List(
  "AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.en.html")
)
