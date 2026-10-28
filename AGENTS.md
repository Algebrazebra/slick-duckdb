# AGENTS.md

This project is a DuckDB extension for the Scala functional relational mapper (FRM) Slick.

## Verification

Run SBT commands from the repository root. In SBT 2, `sbt test` is incremental
and may reuse cached results; use `sbt testFull` when a fresh full-suite result
is needed. Run affected tests for isolated changes.

## Tests and quality checks

- When adapting a Slick testkit test for DuckDB, extend the appropriate testkit class, override the relevant method, and replace the parent class in `DuckDBTest.scala`.
- For independent tests, use a normally discoverable test class without registering it in `DuckDBTest.scala`. See `DuckDBIndexOfTest` and `DuckDBInsertBehaviorTest`.
- Do not manually edit generated GitHub Actions workflows unless the task is specifically about generated output; configure them through `build.sbt`.
