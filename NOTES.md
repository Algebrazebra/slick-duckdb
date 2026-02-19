# Notes

In this file, I'll describe some of the things I learned and issues that I have faced while developing the DuckDB extension.

## Foreign key constraint creation in DuckDB

By default, Slick executes table DDL statements by first creating all the tables and then altering them to add the foreign key constraints.
This way, the order of execution doesn't matter because all tables are already created when the foreign key constraints are added.
DuckDB, however, doesn't allow altering existing tables, so foreign key constraints cannot be added (or modified) after the table was created.
Additionally, when defining the foreign key constraints already in the `CREATE TABLE` DDL, DuckDB validates the constraint at execution time.
As a result, the order of execution matters and Slick queries fail because a foreign key constraint might reference a table that wasn't created yet.

I fixed this by re-ordering the statements using a topological sort algorithm and dirty regex parsing of SQL in this trait: 
[ReorderingSchemaActionExtensionMethods.scala](src/main/scala/components/ReorderingSchemaActionExtensionMethods.scala).

As a sidenote, SQLite has a similar limitation in that it doesn't support altering foreign constraints.
However, in contrast to DuckDB, Sqlite validates the constraint only at insert time.
That's why the Slick extension for Sqlite does not need to reorder the DDL statements.

## JDBC driver doesn't implement `setBlob`

At the time of writing, the DuckDB JDBC driver in version 1.3.2.0 does not implement the `DuckDBPreparedStatement.setBlob` ([GitHub discussion for reference](https://github.com/duckdb/duckdb/discussions/7208
)).
Since `setBytes` is supported, I introduced the workaround of using byte arrays instead of blobs.
Of course, this workaround has its downsides:
increased memory consumption and partial reads and writes are no longer possible with ByteArrays (the whole array must be loaded to access any part of it).

## JDBC driver doesn't implement `getTypeInfo` and `getUDTs`

The DuckDB JDBC driver in version 1.3.2.0 also does not implement `DuckDBPreparedStatement.getTypeInfo` ([GitHub issue for reference](https://github.com/duckdb/duckdb/issues/6759)).
Similarly, the `getUDTs` method isn't implemented either.
The resulting lack of schema introspection required removing the corresponding capabilities in [DuckDBTest.scala](src/test/scala/slick/examples/testkit/DuckDBTest.scala).

## Upserts count as only affecting one row

When performing an upsert operation in Slick via `insertOrUpdate` or `insertOrUpdateAll`, you get back the number of affected rows.
In the `insertOrUpdateAll` test provided by the [testkit](https://github.com/slick/slick-testkit-example), the tests hardcode the assumption that one upsert equals two affected rows.
This is because an upsert can be seen as one added row and one deleted row equals two affected rows.
This holds for a lot of commonly used databases.

DuckDB unfortunately does not calculate the affected rows this way, so that an upsert counts as one affected row.
I overrode the tests to reflect the behavior of DuckDB (see the `testInsertOrUpdateAll` test method in [DuckDBInsertTest.scala](src/test/scala/slick/examples/testkit/DuckDBInsertTest.scala)).

## DDL statement execution returns -1 instead of 0

The Slick [testkit](https://github.com/slick/slick-testkit-example) assumes that DDL operations which don't affect any rows return 0.
The DuckDB JDBC driver unfortunately returns -1, so the tests had to be overridden.
See `DuckDBPreparedStatement.getUpdateCountInternal` for reference.

## Varchar length is not enforced

I was surprised to learn that DuckDB doesn't enforce the varchar length.
The docs recommend using `check` constraints instead.

## In-memory database must have a name set for multiple queries

When using an in-memory DuckDB database, each query sees its own in-memory database.
Seemingly, each query operates on a separate copy of the database. 
This occurs even if the `keepAliveConnection` flag is set to `true`.
The solution is to use a named database along with `keepAliveConnection = true`:
```scala
val inMemoryDb = Database.forURL("jdbc:duckdb:memory:example", driver = "org.duckdb.DuckDBDriver", keepAliveConnection = true)
```

Related GitHub discussion: https://github.com/duckdb/duckdb/discussions/13078