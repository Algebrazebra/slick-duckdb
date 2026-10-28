description          := "Slick database profile for DuckDB"
organization         := "io.github.algebrazebra"
versionScheme        := Some("early-semver")
homepage             := Some(uri("https://github.com/algebrazebra/slick-duckdb"))
licenses             := List(
  "AGPL-3.0" -> uri("https://www.gnu.org/licenses/agpl-3.0.en.html")
)
scmInfo              := Some(
  ScmInfo(
    uri("https://github.com/algebrazebra/slick-duckdb"),
    "scm:git:git@github.com:algebrazebra/slick-duckdb.git"
  )
)
pomIncludeRepository := { _ => false }
developers           := List(
  Developer(
    "algebrazebra",
    "algebrazebra",
    "algebrazebra@users.noreply.github.com",
    uri("https://github.com/algebrazebra")
  )
)

// sbt-github-actions reads these settings at build scope for its matrices.
ThisBuild / scalaVersion               := "2.13.18"
ThisBuild / crossScalaVersions         := Seq("2.12.21", "2.13.18", "3.3.9")
scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) => Seq("-Xsource:3")
    case _            => Seq.empty
  }
}
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))

val duckDbVersion  = settingKey[String]("DuckDB JDBC driver version")
val duckDbVersions = List(
  "1.3.2.0",
  "1.4.1.0",
  "1.5.2.0",
  "2.0.0.0"
)
duckDbVersion                                              := sys.props.getOrElse("duckdb.version", "1.3.2.0")
ThisBuild / githubWorkflowBuildMatrixAdditions += "duckdb" -> duckDbVersions
// The workflow check starts an sbt server without duckdb.version. Stop it so
// each matrix job's build starts with the selected JDBC driver's JVM property.
ThisBuild / githubWorkflowBuild                            := Seq(
  WorkflowStep.Sbt(List("shutdown"), name = Some("Stop workflow-check server")),
  WorkflowStep.Run(
    commands = List(
      "sbt -Dduckdb.version=${{ matrix.duckdb }} '++ ${{ matrix.scala }}; testFull; shutdown'"
    ),
    name = Some("Build project")
  )
)
// Only the baseline driver uploads artifacts for the Scala-only publish matrix.
// Follow symlinks because sbt 2 stores build outputs in its content-addressed cache.
ThisBuild / githubWorkflowGeneratedUploadSteps             := Seq(
  WorkflowStep.Run(
    commands = List("tar chf targets.tar target/out/jvm project/target"),
    name = Some("Compress target directories"),
    cond = Some("matrix.duckdb == '1.3.2.0'")
  ),
  WorkflowStep.Use(
    ref = UseRef.Public("actions", "upload-artifact", "v5"),
    name = Some("Upload target directories"),
    cond = Some("matrix.duckdb == '1.3.2.0'"),
    params = Map(
      "name" -> "target-${{ matrix.os }}-${{ matrix.scala }}-${{ matrix.java }}",
      "path" -> "targets.tar"
    )
  )
)

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick"           % "3.6.1",
  "org.duckdb"          % "duckdb_jdbc"     % duckDbVersion.value % Test, // scala-steward:off
  "com.github.sbt"      % "junit-interface" % "0.13.3"            % Test,
  "ch.qos.logback"      % "logback-classic" % "1.6.6"             % Test,
  "org.scalatest"      %% "scalatest"       % "3.2.20"            % Test,
  "com.typesafe.slick" %% "slick-testkit"   % "3.6.1"             % Test
) ++ (if (scalaVersion.value.startsWith("3")) Nil
      else Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value))

scalacOptions += "-deprecation"

Test / parallelExecution := false

logBuffered := false

run / fork := true

testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s", "-a")
