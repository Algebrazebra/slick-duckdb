
# Slick-DuckDB

[![CI](https://github.com/Algebrazebra/slick-duckdb/actions/workflows/ci.yml/badge.svg)](https://github.com/Algebrazebra/slick-duckdb/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.algebrazebra/slick-duckdb_2.13.svg)](https://central.sonatype.com/artifact/io.github.algebrazebra/slick-duckdb_2.13/overview)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

[Slick](https://github.com/slick/slick) extension for DuckDB for your type-safe and in-memory data processing needs.

| `slick-duckdb` version | Supported [DuckDB JDBC](https://github.com/duckdb/duckdb-java) versions |
| --- | --- |
| 0.1.0 | `1.3.2.0`, `1.4.1.0` |
| 0.1.1 | `2.0.0.0`, `1.3.2.0`, `1.4.1.0`, `1.5.2.0` |

Future versions will likely work, but of course your mileage may vary since they are currently not being tested.

Additionally, the extension does not fully support or map all DuckDB features to Slick.
Known limitations are:
- Blobs are handled as byte arrays as a workaround to missing JDBC driver functionality; be cautious when sizing byte arrays and watch memory consumption and performance
- DuckDB extensions, and syntax related to them, are not supported beyond the SQL standard


## How to use it

### Installation

Add `slick-duckdb` to your `build.sbt` along with the DuckDB JDBC driver:

```build.sbt
libraryDependencies += "org.duckdb" % "duckdb_jdbc" % "1.4.1.0"
libraryDependencies += "io.github.algebrazebra" % "slick-duckdb_2.13" % "0.1.0"
```

### Usage

To demonstrate the usage, let's create a simple table and query it:

```scala

import duckdbslick.DuckDBProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*

// Here we define the `User` table using Slick
case class User(id: Int, name: String, age: Int)
class Users(tag: Tag) extends Table[User](tag, "users") {
  def id   = column[Int]("id", O.PrimaryKey)
  def name = column[String]("name")
  def age  = column[Int]("age")
  def *    = (id, name, age) <> (User.tupled, User.unapply)
}
val users = TableQuery[Users]

// Next we create the DuckDB database connection.
// If `example.duckdb` does not exist, the file will be created automatically.
val db = Database.forURL("jdbc:duckdb:./example.duckdb", driver = "org.duckdb.DuckDBDriver")

// Alternatively, you can specify `jdbc:duckdb:memory:example` as the URL to use DuckDB in in-memory mode.
// The `keepAliveConnection` parameter must be set.
// If it's not set, each query will be executed against its own fresh in-memory database.
// For the same reason, the database name (here: `example`) must be specified.
val inMemoryDb = Database.forURL(
  "jdbc:duckdb:memory:example",
  driver = "org.duckdb.DuckDBDriver",
  keepAliveConnection = true
)


// Let's create the table and insert the example user records
val exampleUsers = Seq(
  User(1, "Alice", 30),
  User(2, "Bob", 25),
  User(3, "Charlie", 35),
  User(4, "Diana", 28)
)
val insertUsers = db.run(
  DBIO.seq(
    users.schema.createIfNotExists,
    users ++= exampleUsers
  )
)
Await.result(insertUsers, 5.seconds)

// Finally, we can execute queries against the table with the Slick DSL
val queryUsersOlderThan25 = users.filter(_.age > 25)
val usersOlderThan25 = Await.result(db.run(queryUsersOlderThan25.result), 5.seconds)

println("Users older than 25:")
usersOlderThan25.foreach { user =>
  println(s"  - ${user.name} (ID: ${user.id}, Age: ${user.age})")
}
assert(usersOlderThan25.size == exampleUsers.count(_.age > 25))

// After all this fun stuff, we have to tidy up
db.close()
```

### Features worth noting

#### Nanosecond time-zone timestamps

With DuckDB JDBC `2.0.0.0` or newer, use `TimestampTzNs` for a
`TIMESTAMPTZ_NS` column:

```scala
import duckdbslick.types.TimestampTzNs
import duckdbslick.DuckDBProfile.api.*
import java.time.OffsetDateTime

class Events(tag: Tag) extends Table[(Int, Option[TimestampTzNs])](tag, "events") {
  def id = column[Int]("id", O.PrimaryKey)
  def happenedAt = column[Option[TimestampTzNs]]("happened_at")
  def * = (id, happenedAt)
}

val value = TimestampTzNs.from(
  OffsetDateTime.parse("1969-12-31T23:59:59.123456789+02:00")
)
```

`TimestampTzNs` stores an absolute instant with nanosecond precision. Unlike
Slick's existing `Timestamp` and `Instant` columns, it creates DuckDB's
time-zone-aware `TIMESTAMPTZ_NS` type instead of a microsecond `TIMESTAMP`.
Unlike Slick's `OffsetDateTime` column, it stores a timestamp rather than text.
This keeps sub-microsecond values available to DuckDB queries. The input offset
identifies the instant; DuckDB does not retain the original offset or zone name.
Use `Option[TimestampTzNs]` for nullable columns. Values must fit DuckDB's
nanosecond timestamp range.

Existing timestamp mappings retain their behavior. DuckDB JDBC 1.x cannot use
`TimestampTzNs`; schema generation reports that JDBC 2.0 or newer is required.

#### Insert returning

With DuckDB JDBC `2.0.0.0` or newer, Slick can return generated keys and other
columns from an insert. Single-row `+=`, multiple-row `++=` and `insertAll`,
and `returning ... into` are supported. For example:

```scala
class Events(tag: Tag) extends Table[(Int, String)](tag, "events") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def * = (id, name)
}
val events = TableQuery[Events]
Await.result(db.run(events.schema.create), 5.seconds)
val insertName = events.map(_.name) returning events.map(_.id)
val generatedId = Await.result(db.run(insertName += "created"), 5.seconds)
```

Returning an entire inserted row or a selection of columns also works. A
`returning` insertOrUpdate returns `Some(key)` for an insert and `None` for an
update when matching by primary key. A zero-key upsert with an existing
`O.Unique` value raises a unique-constraint error, as it does without
`returning`; supply the primary key to update that row. Slick also rejects
returning columns from another table.

DuckDB JDBC `1.x` does not provide the generated-key path Slick needs, so the
three Slick returning capabilities remain disabled with those drivers and
`returning` insert actions fail with a `SlickException`. The profile reads the
driver JAR's `Bundle-Version` manifest attribute; if it is unavailable, the
capabilities remain disabled.

#### Upserts

`insertOrUpdate` and `insertOrUpdateAll` match existing rows by the table's
primary key, including composite primary keys. Passing `0` for an `O.AutoInc`
column requests a generated value. Other unique constraints remain enforced:
a unique value owned by another row causes the statement to fail and does not
select that row for an update. Update an existing row by supplying its primary key.

If a generated key already exists, the operation fails without changing the
existing row. These rules also apply to batches mixing explicit and zero keys.

#### Check constraints

DuckDB-specific named check constraints use the column-oriented `O.Check`
option. Multiple constraints may be declared on a column, and a predicate may
refer to other columns included in the same table DDL:

```scala
class Ranges(tag: Tag) extends Table[(Option[Int], Int)](tag, "ranges") {
  def minimum = column[Option[Int]]("minimum")
  def maximum = column[Int](
    "maximum",
    O.Check[Int]("maximum_non_negative")(_ >= 0),
    O.Check[Int]("valid_range")(max => minimum <= max.?)
  )
  def * = (minimum, maximum)
}
```

Predicates may return `Rep[Boolean]` or `Rep[Option[Boolean]]`. As in SQL, a
check result of `NULL` passes. Predicates are schema-definition code that may
be evaluated repeatedly, so they must be deterministic, side-effect-free, and
independent of the mutable application state.

Checks support scalar comparisons, Boolean and arithmetic operators, casts,
case expressions, literal products such as `IN`, safely named
`SimpleFunction`s, and functions supported by the DuckDB query builder.
Subqueries, binds, aggregates, windows, sequences, raw SQL nodes,
cross-table references, and references to columns omitted from the table DDL
are rejected while Slick constructs the DDL.

#### Length constraints

Use `O.Length` on a `VARCHAR` column to enforce its maximum length. Its
`varying` parameter controls whether the column is emitted as `VARCHAR` or
`CHAR`. DuckDB does not enforce declared `VARCHAR` lengths, so this option is
emitted as a DuckDB `CHECK` constraint and values longer than the declared
limit are rejected by the database:

```scala
class Users(tag: Tag) extends Table[(Int, String)](tag, "users") {
  def id = column[Int]("id", O.PrimaryKey)
  def name = column[String]("name", O.Length(100))
  def * = (id, name)
}
```
