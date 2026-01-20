
# Slick-DuckDB

[Slick](https://github.com/slick/slick) extension for DuckDB for your type-safe and in-memory data processing needs.

This extension was written and tested for [DuckDB JDBC driver](https://github.com/duckdb/duckdb-java) version `1.3.2.0`.
Future versions will likely work, but of course your mileage may vary since they are currently not being tested.

Additionally, the extension does not fully support or map all DuckDB features to Slick.
Known limitations are:
- Blobs are handled as byte arrays as a workaround to missing JDBC driver functionality; be cautious when sizing byte arrays and watch memory consumption and performance
- Check constraints are not supported
- DuckDB extensions, and syntax related to them, are not supported beyond the SQL standard


## How to use it

### Installation

Add the `slick-duckdb` to your `build.sbt`:

```build.sbt
libraryDependencies += "io.github.algebrazebra" %% "slick-duckdb" % "0.0.1"
```

### Usage



TODO: how to install

TODO: an example how to set up database connection and query
TODO: Database creation factories

TODO: Implement DatabaseMetaData.getTypeInfo endpoint in DuckDB JDBC Driver
TODO: "Check constraints" support

