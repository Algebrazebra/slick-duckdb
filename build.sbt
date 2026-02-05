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

ThisBuild / scalaVersion               := "2.13.16"
ThisBuild / crossScalaVersions         := Seq("2.12.20", "2.13.16")
ThisBuild / scalacOptions += "-Xsource:3"
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))

val duckDbVersion  = settingKey[String]("DuckDB JDBC driver version")
val duckDbVersions = List(
  "1.3.2.0",
  "1.4.1.0"
)
ThisBuild / duckDbVersion := sys.props.getOrElse("duckdb.version", "1.3.2.0")
ThisBuild / githubWorkflowBuildMatrixAdditions += "duckdb" -> duckDbVersions
ThisBuild / githubWorkflowBuildSbtStepPreamble += s"-Dduckdb.version=$${{ matrix.duckdb }}"
ThisBuild / githubWorkflowGeneratedDownloadSteps := {
  val scalas = (ThisBuild / crossScalaVersions).value
  scalas.flatMap { v =>
    duckDbVersions.map { db =>
      WorkflowStep.Use(
        UseRef.Public("actions", "download-artifact", "v6"),
        Map("name" -> s"target-$${{ matrix.os }}-$v-$${{ matrix.java }}-$db")
      ) +:
      WorkflowStep.Run(
        List("tar xf targets.tar", "rm targets.tar"),
        name = Some(s"Inflate target directories ($v, duckdb-$db)")
      ) +: Nil
    }
  }.flatten
}

libraryDependencies ++= List(
  "org.duckdb"          % "duckdb_jdbc"     % duckDbVersion.value,
  "com.github.sbt"      % "junit-interface" % "0.13.3"           % Test,
  "ch.qos.logback"      % "logback-classic" % "1.5.27"           % Test,
  "org.scalatest"      %% "scalatest"       % "3.2.19"           % Test,
  "com.typesafe.slick" %% "slick-testkit"   % "3.6.1"            % Test,
  "org.scala-lang"      % "scala-reflect"   % scalaVersion.value % Test
)

scalacOptions += "-deprecation"

Test / parallelExecution := false

logBuffered := false

run / fork := true

testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a")

licenses := List(
  "AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.en.html")
)
