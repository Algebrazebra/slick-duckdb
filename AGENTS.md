# AGENTS.md

This project is a DuckDB extension for the Scala functional relational mapper (FRM) Slick.

## Verification

Run SBT commands from the repository root. In SBT 2, `sbt test` is incremental
and may reuse cached results; use `sbt testFull` when a fresh full-suite result
is needed. Use Scala 2.13.18 and DuckDB JDBC 2.0.0.0 for local tests. Invoke
SBT with `-Dsbt.server.autostart=false -Dduckdb.version=2.0.0.0` so a running
SBT 2 server cannot reuse another driver version. Run affected tests while
editing and `testFull` once before opening a PR. CI tests the full matrix.

## Tests and quality checks

- When adapting a Slick testkit test for DuckDB, extend the appropriate testkit class, override the relevant method, and replace the parent class in `DuckDBTest.scala`.
- For independent tests, use a normally discoverable test class without registering it in `DuckDBTest.scala`. See `DuckDBIndexOfTest` and `DuckDBInsertBehaviorTest`.
- Do not manually edit generated GitHub Actions workflows unless the task is specifically about generated output; configure them through `build.sbt`.
