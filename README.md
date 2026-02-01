
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

Add `slick-duckdb` to your `build.sbt` along with the DuckDB JDBC driver:

```build.sbt
libraryDependencies += "org.duckdb" % "duckdb_jdbc" % "1.3.2.0",
libraryDependencies += "io.github.algebrazebra" %% "slick-duckdb" % "0.0.1"
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



TODO: add matrix testing for different DuckDB versions
TODO: set up publishing to Maven Central with sbt-ci-release

TODO: Implement DatabaseMetaData.getTypeInfo endpoint in DuckDB JDBC Driver
TODO: "Check constraints" support


