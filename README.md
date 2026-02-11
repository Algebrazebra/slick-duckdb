
# Slick-DuckDB

[![CI](https://github.com/Algebrazebra/slick-duckdb/actions/workflows/ci.yml/badge.svg)](https://github.com/Algebrazebra/slick-duckdb/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.algebrazebra/slick-duckdb_2.13.svg)](https://central.sonatype.com/artifact/io.github.algebrazebra/slick-duckdb_2.13/overview)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

[Slick](https://github.com/slick/slick) extension for DuckDB for your type-safe and in-memory data processing needs.

This extension was written and tested for [DuckDB JDBC driver](https://github.com/duckdb/duckdb-java) version `1.3.2.0`.
I have since included other versions of the JDBC driver in the build matrix.
Future versions will likely work, but of course your mileage may vary since they are currently not being tested.

Additionally, the extension does not fully support or map all DuckDB features to Slick.
Known limitations are:
- Blobs are handled as byte arrays as a workaround to missing JDBC driver functionality; be cautious when sizing byte arrays and watch memory consumption and performance
- Check constraints are not supported
- DuckDB extensions, and syntax related to them, are not supported beyond the SQL standard


## How to use it

### Installation

Add `slick-duckdb` to your `build.sbt` along with the DuckDB JDBC driver:

```build.sbt
libraryDependencies += "org.duckdb" % "duckdb_jdbc" % "1.3.2.0",
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
val inMemoryDb = Database.forURL("jdbc:duckdb:memory:example", driver = "org.duckdb.DuckDBDriver", keepAliveConnection = true)


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
