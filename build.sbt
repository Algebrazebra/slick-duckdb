import scala.language.postfixOps

organization         := "io.github.algebrazebra"
name                 := "slick-duckdb"
versionScheme        := Some("early-semver")
homepage             := Some(url("https://github.com/algebrazebra/slick-duckdb"))
scmInfo              := Some(
  ScmInfo(
    url("https://github.com/algebrazebra/slick-duckdb"),
    "scm:git:git@github.com:algebrazebra/slick-duckdb.git"
  )
)
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

ThisBuild / scalaVersion               := "2.13.18"
ThisBuild / crossScalaVersions         := Seq("2.12.21", "2.13.18", "3.3.1")
ThisBuild / scalacOptions += "-Xsource:3"
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))

val duckDbVersion  = settingKey[String]("DuckDB JDBC driver version")
val duckDbVersions = List(
  "1.3.2.0",
  "1.4.1.0"
)
ThisBuild / duckDbVersion                                  := sys.props.getOrElse("duckdb.version", "1.3.2.0")
ThisBuild / githubWorkflowBuildMatrixAdditions += "duckdb" -> duckDbVersions
ThisBuild / githubWorkflowGeneratedUploadSteps := Seq(
  WorkflowStep.Run(
    commands = List("tar cf targets.tar target project/target"),
    name = Some("Compress target directories")
  ),
  WorkflowStep.Use(
    ref = UseRef.Public("actions", "upload-artifact", "v5"),
    name = Some("Upload target directories"),
    params = Map(
      "name" -> "target-${{ matrix.os }}-${{ matrix.scala }}-${{ matrix.java }}-${{ matrix.duckdb }}",
      "path" -> "targets.tar"
    )
  )
)

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick"           % "3.6.1",
  "org.duckdb"          % "duckdb_jdbc"     % duckDbVersion.value % Test,
  "com.github.sbt"      % "junit-interface" % "0.13.3"            % Test,
  "ch.qos.logback"      % "logback-classic" % "1.5.27"            % Test,
  "org.scalatest"      %% "scalatest"       % "3.2.19"            % Test,
  "com.typesafe.slick" %% "slick-testkit"   % "3.6.1"             % Test,
) ++ (if (scalaVersion.value.startsWith("3")) Nil else Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value))

scalacOptions += "-deprecation"

Test / parallelExecution := false

logBuffered := false

run / fork := true

testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a")

licenses := List(
  "AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.en.html")
)
