
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
- On DuckDB JDBC versions before 2.0, auto-increment sequences for schema names containing a double quote are stored in `main` due to driver limitations. Drop those tables with Slick's `schema.drop` before dropping their schema; a raw `DROP SCHEMA ... CASCADE` leaves the sequence in `main`. On version 2.0 and newer, the sequence stays in its table's schema.
- Slick's `createModel` works with DuckDB JDBC 1.5.2.0 and newer, but DuckDB reports `CHAR` as `VARCHAR`, omits declared string lengths, and replaces requested foreign-key names. The resulting model cannot reproduce those schema details exactly.


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

#### JSON creation

`Json(String)` holds raw JSON text: `Json("2")` is a number and
`Json("\"duck\"")` is a string. The explicit constructors encode Scala values:
`Json.number` accepts `Int`, `Long`, `BigInt`, and `BigDecimal`;
`Json.boolean(true)`, `Json.string("duck")`, and `Json.nullValue` produce the
corresponding JSON values. `Json.string` escapes quotes, backslashes, control
characters, and Unicode. There are no floating-point overloads. A present
`Json.nullValue` is JSON null; `None: Option[Json]` is SQL `NULL`.

The profile API exposes DuckDB's creation functions on the supported JDBC
1.3–2.0 drivers:

```scala
import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.Json

val quoted = Query(toJson("duck".bind))
val nullable = Query(toJson(Option.empty[String].bind))
val array = Query(jsonArray("duck".bind, 2.bind, Json.nullValue.bind))
val obj = Query(jsonObject("name".bind -> "duck".bind, "count".bind -> 2.bind))
val patched = Query(jsonMergePatch(
  Json("{\"count\":1}").bind,
  Json("{\"count\":2}").bind
))
```

`toJson` accepts both nonnullable and nullable Slick expressions, returning
`Rep[Json]` or `Rep[Option[Json]]` respectively. When passing the overloaded
method as a function, use an explicit lambda, such as
`values.map(value => toJson(value))`.
Strings are JSON-quoted, while JSON input retains its JSON value. These
methods work with Slick SQL values that already have a column mapping; the
profile does not add SQL `LIST`, `STRUCT`, or `MAP` mappings. `jsonArray` and
`jsonObject` accept heterogeneous expressions, including no arguments.
Their SQL `NULL` values become JSON null elements or fields. Object keys
must be nonnullable `Rep[String]` values.

`jsonMergePatch` accepts both nonnullable and nullable JSON arguments.
A SQL `NULL` document is ignored, while a SQL `NULL` patch produces SQL `NULL`.
The result type follows the patch: `Rep[Json]` for a nonnullable patch and
`Rep[Option[Json]]` for a nullable patch, regardless of the document type.
A JSON null field in a patch deletes that field. DuckDB's `json_quote`,
`array_to_json`, and `row_to_json` are SQL aliases of `to_json` for their respective inputs; the Slick API uses
the canonical `toJson` method.

#### JSON aggregates

Use the query extensions from `DuckDBProfile.api` to reduce rows to JSON:

```scala
val amounts: Rep[Option[Json]] = rows.map(_.amount).jsonGroupArray
val byKey: Rep[Option[Json]] =
  rows.map(row => (row.key, row.amount)).jsonGroupObject
val structures: Rep[Option[Json]] = rows.map(_.document).jsonGroupStructure

val byCategory = rows.groupBy(_.category).map { case (category, group) =>
  (category, group.map(_.amount).jsonGroupArray)
}
```

`jsonGroupArray` accepts mapped SQL numbers, booleans, strings, and JSON,
including nullable columns. SQL strings become JSON strings; SQL JSON values
remain JSON values. `jsonGroupObject` requires non-null SQL string keys and
accepts the same value types. The default `jsonGroupStructure` accepts only
`Json` or `Option[Json]` documents. All default results are
`Rep[Option[Json]]`: an aggregate over zero rows returns SQL `NULL` (`None`),
while a row containing SQL `NULL` becomes
a JSON `null` array element or object value. A JSON `null` input remains a
present JSON value. Grouped queries return no row for a missing category.

For an alternate Scala JSON type `J`, use `jsonGroupArrayAs(mapping)`,
`jsonGroupObjectAs(mapping)`, or `jsonGroupStructureAs(mapping)` with an
explicit `BaseColumnType[J]` backed by SQL `JSON`. The result is
`Rep[Option[J]]`, including `None` for empty input. `jsonGroupStructureAs`
requires `J` or `Option[J]` input. The raw `Json` mapping
remains the default.

Duplicate object keys are preserved as duplicate members, so consumers should
not assume last-write-wins lookup. Array element order and object member order
are unspecified without an ordered input strategy. DuckDB does not accept
aggregate-local `ORDER BY`, `DISTINCT`, or `FILTER` clauses for these JSON
macros; these methods do not expose those clauses.

#### JSON patching and normalization

DuckDB JDBC 2.0 adds four JSON patching and normalization methods to JSON
expressions. Patches can mix required and nullable `Json` expressions in the
same `jsonDeepMerge` call:

```scala
import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.Json

val document = Json("""{"a":1}""").bind
val requiredPatch = Json("""{"a":2}""").bind
val nullablePatch: Rep[Option[Json]] = Option(Json("""{"b":3}""")).bind
val merged: Rep[Option[Json]] =
  document.jsonDeepMerge(requiredPatch, nullablePatch)

val diff: Rep[Json] = document.jsonMergePatchDiff(requiredPatch)
val normalized: Rep[String] = document.jsonNormalize
val stripped: Rep[Json] = document.jsonStripNulls
```

With only required patches, `jsonDeepMerge` returns `Rep[Json]`. With a
nullable patch, it returns `Rep[Option[Json]]`. Patches run left to right: a
SQL `NULL` patch produces a NULL intermediate result, and a later patch can
produce JSON again. A JSON-null object member instead keeps the original
member; `jsonMergePatch` deletes it. `jsonNormalize` returns text because
DuckDB reports `VARCHAR`. For a sequence containing both patch types,
declare its element type as `Seq[JsonDeepMergePatch]` before expanding it with
`: _*`.

#### JSON extraction

`Json` expressions support DuckDB's four extraction functions and both arrow
operators on every supported JDBC version:

```scala
import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.{Json, JsonScalarPath}

val document = Json("""{"species":[{"name":"duck"}]}""").bind
val first = JsonScalarPath("$.species[0]").bind
val name = JsonScalarPath("$.name").bind

val species: Rep[Option[Json]] = document -> first
val speciesName: Rep[Option[String]] = (document -> first) ->> name
// speciesName yields Some("duck").
```

| Method | SQL | Result for `Rep[Json]` |
| --- | --- | --- |
| `jsonExists(path)` | `json_exists` | `Rep[Boolean]` |
| `jsonExtract(path)` | `json_extract` | `Rep[Option[Json]]` |
| `jsonExtractText(path)` | `json_extract_string` | `Rep[Option[String]]` |
| `jsonValue(path)` | `json_value` | `Rep[Option[Json]]` |
| `->(path)` | `->` | `Rep[Option[Json]]` |
| `->>(path)` | `->>` | `Rep[Option[String]]` |

`jsonValue` returns SQL `NULL` for a non-scalar selection. DuckDB reports its
native result as `VARCHAR`, so the wrapper casts it to SQL `JSON` for the
declared `Rep[Option[Json]]` type. Missing paths also yield SQL `NULL` for JSON
and text extraction. `jsonExists` returns an optional Boolean when the
document or path is nullable. A JSON `null` selected by
`jsonExtract` or `->` stays `Some(Json("null"))`; selecting it as text with
`jsonExtractText` or `->>` yields SQL `NULL`.

Use `JsonScalarPath` for extraction. It validates literal JSONPath paths such
as `$.species[0]` and quoted keys such as `$."a.b"`; it also accepts JSON
Pointers such as `/species/0` and the empty root pointer. Array indexes start
at zero. The supported JSONPath syntax includes root `$`, dot-separated
keys, quoted keys, nonnegative array indexes, and `[#-n]` indexes from the end.
Wildcard, filter, and path-list selections are rejected because DuckDB returns
SQL lists for them; multi-result extraction is deferred. Paths from arbitrary
database columns cannot be treated as validated scalar paths. The existing
unrestricted `JsonPath` remains available for mutation methods.

The generated arrow expressions are parenthesized, so they remain grouped in
equality, `AND`, and `OR` predicates across DuckDB 1.x and 2.0. Documents and
paths can be bound as shown above. See the [DuckDB JSON extraction reference](https://duckdb.org/docs/2.0/data/json/json_functions#json-extraction-functions).

#### JSON scalar functions

The eight DuckDB JSON scalar functions are available through
`DuckDBProfile.api` on JDBC 1.3–2.0:

```scala
import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.Json

val document = Json("[1,2,3]").bind
val count: Rep[BigInt] = document.jsonArrayLength
val contains: Rep[Boolean] = document.jsonContains(Json.number(2).bind)
val valid: Rep[Boolean] = jsonValid("[1,2,3]".bind)
val parsed: Rep[Json] = jsonParse(" [ 1, 2, 3 ] ".bind)
```

`jsonArrayLength` returns lossless `BigInt` for DuckDB's unsigned `UBIGINT`.
`jsonKeys` returns `Seq[String]` from a `VARCHAR[]`, in object key order.
`jsonPretty` and `jsonType` return strings; `jsonStructure` returns `Json`.
The receiver methods also work on `Rep[Option[Json]]` and return optional
results. `jsonContains` accepts a JSON needle: use `Json.string("duck")` for
a string needle, not raw `Json("duck")`. A present `Json.nullValue` differs
from SQL `NULL` (`None: Option[Json]`).

`jsonArrayLength`, `jsonKeys`, and `jsonType` also accept a
`JsonScalarPath` value. It validates a single-result JSONPath or JSON Pointer
and is bound as a SQL parameter. These overloads return `Rep[Option[...]]`
because a missing path yields SQL `NULL`; JSON `null` at a present path still
returns the text `"NULL"` from `jsonType`. JSON array indexes start at zero.
Wildcard paths and path lists need separate list result mappings and are not
accepted by these overloads.

`jsonValid` accepts raw SQL text and returns `false` for malformed JSON.
`jsonParse` parses and minifies raw text into SQL `JSON`, raising a DuckDB
error for malformed input. Both accept nullable text expressions and return
optional results for SQL `NULL`. `jsonParse` differs from `toJson`: the
latter JSON-quotes an ordinary SQL string.

#### JSON mutation

With DuckDB JDBC `2.0.0.0` or newer, `Json` columns support `jsonSet`,
`jsonInsert`, `jsonReplace`, and `jsonRemove`. Each method returns a typed JSON
expression, so it can be used in a Slick query:

```scala
import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.{Json, JsonPath}

class Documents(tag: Tag) extends Table[(Int, Json)](tag, "documents") {
  def id = column[Int]("id", O.PrimaryKey)
  def body = column[Json]("body")
  def * = (id, body)
}
val documents = TableQuery[Documents]

val edited = documents.map(_.body.jsonSet(
  JsonPath("$.user.name").bind,
  Json("\"duck\"").bind
))
// For {"user":{}}, edited returns {"user":{"name":"duck"}}.
```

| Method | DuckDB function | Effect at the path |
| --- | --- | --- |
| `jsonSet(path, value)` | `json_set` | Add or overwrite |
| `jsonInsert(path, value)` | `json_insert` | Add only if missing |
| `jsonReplace(path, value)` | `json_replace` | Overwrite only if present |
| `jsonRemove(path)` | `json_remove` | Remove the element; returns nullable JSON |

`Json` contains JSON text, rather than an ordinary SQL string. For example,
`Json("2")` is a JSON number, `Json("\"duck\"")` is a JSON string, and
`Json("null")` is JSON `null`. Use `Option[Json]` for SQL `NULL`. DuckDB validates
JSON text when executing the query. `JsonPath` contains a DuckDB JSONPath such
as `$.items[0]` or a JSON Pointer such as `/items/0`; JSON array indexes start
at zero. Paths and values can be bound as shown above. Nullable documents,
paths, or values produce a nullable result, and SQL `NULL` propagates.
`jsonRemove` always returns `Rep[Option[Json]]` because removing the root path
`$` yields SQL `NULL`.

DuckDB's mutation functions do not support wildcard paths or JSONPath filters.
The wrapper passes paths to DuckDB for validation and follows its behavior for
missing paths and array indexes. See the [DuckDB JSON function reference](https://duckdb.org/docs/2.0/data/json/json_functions#json-mutation-functions)
and [JSON path guide](https://duckdb.org/docs/2.0/data/json/overview#jsonpath-and-json-pointer-syntax).

The typed JSON column mapping works with the supported JDBC 1.x drivers, but
calling any of these four mutation methods on 1.x throws a `SlickException`
before SQL execution. Existing plain SQL and JSON queries are unchanged.

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
