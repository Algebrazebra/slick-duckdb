import _root_.io.github.nafg.mergify.dsl.*

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / scalacOptions += "-Xsource:3"

mergifyExtraConditions := Seq(
  (Attr.Author :== "scala-steward") ||
    (Attr.Author :== "slick-scala-steward[bot]") ||
    (Attr.Author :== "renovate[bot]")
)

libraryDependencies ++= List(
  "com.github.sbt" % "junit-interface" % "0.13.3"  % Test,
  "ch.qos.logback" % "logback-classic" % "1.5.18"  % Test,
  "org.duckdb"     % "duckdb_jdbc"     % "1.3.1.0" % Test,
)

scalacOptions += "-deprecation"

Test / parallelExecution := false

logBuffered := false

run / fork := true

testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a")
libraryDependencies += "com.typesafe.slick" %% "slick-testkit" % "3.6.1"
libraryDependencies += "org.scala-lang"      % "scala-reflect" % scalaVersion.value

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))
ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Run(List("docker compose up -d"), name = Some("Start database"))
