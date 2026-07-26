
# Slick-DuckDB

[![CI](https://github.com/Algebrazebra/slick-duckdb/actions/workflows/ci.yml/badge.svg)](https://github.com/Algebrazebra/slick-duckdb/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.algebrazebra/slick-duckdb_2.13.svg)](https://central.sonatype.com/artifact/io.github.algebrazebra/slick-duckdb_2.13/overview)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

[Slick](https://github.com/slick/slick) extension for DuckDB for your type-safe and in-memory data processing needs.

This extension was written and tested for [DuckDB JDBC driver](https://github.com/duckdb/duckdb-java) versions `1.3.2.0`, `1.4.1.0`, and `1.5.1.0`.
Future versions will likely work, but of course your mileage may vary since they are currently not being tested.

Additionally, the extension does not fully support or map all DuckDB features to Slick.
Known limitations are:
- Blobs are handled as byte arrays as a workaround to missing JDBC driver functionality; be cautious when sizing byte arrays and watch memory consumption and performance
- `O.Length` is enforced through generated DuckDB check constraints
- DuckDB extensions, and syntax related to them, are not supported beyond the SQL standard

Applications that previously stored oversized values despite declaring `O.Length` will now receive constraint violations. `O.Length(n, varying = false)` provides the same maximum-length guarantee, but DuckDB does not provide fixed-width padding semantics.

## Check constraints

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
independent of mutable application state.

Checks support scalar comparisons, Boolean and arithmetic operators, casts,
case expressions, literal products such as `IN`, safely named
`SimpleFunction`s, and functions supported by the DuckDB query builder.
Subqueries, binds, aggregates, windows, sequences, raw SQL nodes,
cross-table references, and references to columns omitted from the table DDL
are rejected while Slick constructs the DDL.

## How to use it

### Installation

Add `slick-duckdb` to your `build.sbt` along with the DuckDB JDBC driver:

```build.sbt
libraryDependencies += "org.duckdb" % "duckdb_jdbc" % "1.5.1.0",
libraryDependencies += "io.github.algebrazebra" % "slick-duckdb_2.13" % "0.0.2"
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
