# Upsert row identity in Slick profiles

This comparison uses Slick **3.6.1**, the version used by this repository, and
slick-pg **0.23.1**, whose [build uses Slick 3.6.1](https://github.com/tminglei/slick-pg/blob/v0.23.1/build.sbt#L65-L71).
It describes source code and the SQL it generates. It is not a runtime test
matrix against the other database engines. Outcomes inferred from that SQL are
identified below; database configuration and custom profile overrides can change
them.

## What the inherited test establishes

[`InsertTest.testInertOrUpdateWithAutoIncAndUniqueColumn`](https://github.com/slick/slick/blob/v3.6.1/slick-testkit/src/main/scala/com/typesafe/slick/testkit/tests/InsertTest.scala#L406-L426)
defines an auto-increment primary key, a unique `name`, and a `location`. It
upserts `(0, "tiger", "NY")`, then `(0, "tiger", "CA")`, and expects the same ID
with the updated location. The method name contains `Inert` in upstream Slick.

The test returns a successful empty action when
`JdbcCapabilities.insertOrUpdate` is absent. Of the nine built-in JDBC profiles
in Slick 3.6.1, **only MySQL retains that capability**. Consequently, the test's
presence in the common testkit does not show that every profile updates a row
through an independently unique column. The individual capability definitions
and implementations are linked in the table below.

The capability itself documents native upsert availability, with client-side
emulation otherwise; it does not define alternate unique columns as row
identity. The single-row API documentation describes primary-key matching.
[Capability contract](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcCapabilities.scala#L11-L16),
[single-row API contract](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcActionComponent.scala#L426-L439).

## Built-in JDBC profiles

The ordinary API column describes this test's **AutoInc** table, without
`returning`. A skipped test still leaves an ordinary `insertOrUpdate` method
available. A native SQL builder also does not necessarily mean the profile
advertises the capability: several profiles explicitly enable their server-side
implementation while removing the capability.

| Profile | Capability / inherited test | Ordinary `insertOrUpdate` path and row identity |
| --- | --- | --- |
| MySQL | Present; test executes. | `INSERT ... ON DUPLICATE KEY UPDATE`. Either a primary-key or a unique-index conflict can select the existing row; AutoInc columns are excluded from the update assignments. [Capability definition](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/MySQLProfile.scala#L65-L72), [builder](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/MySQLProfile.scala#L282-L297). |
| DB2 | Absent; skipped. | Server-side standard `MERGE`, matching primary keys and omitting AutoInc columns from the insert branch. [Profile configuration](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/DB2Profile.scala#L45-L55), [shared builder](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcStatementBuilderComponent.scala#L603-L638). |
| Derby | Absent; skipped. | Client-side primary-key check, followed by an update by primary key or a soft insert that omits AutoInc columns. [Capability removal](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/DerbyProfile.scala#L81-L101), [default routing](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcActionComponent.scala#L364-L367), [emulation](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcActionComponent.scala#L678-L695). |
| H2 | Absent; skipped. | For an AutoInc table, the action composer selects client-side primary-key emulation. Its separate `MERGE INTO` builder is used when there are no AutoInc fields. [Capability removal](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/H2Profile.scala#L43-L53), [AutoInc routing](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/H2Profile.scala#L174-L187). |
| HSQLDB | Absent; skipped. | Server-side standard `MERGE`, matching primary keys; the insert branch omits AutoInc columns. [Profile configuration](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/HsqldbProfile.scala#L41-L75), [shared builder](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcStatementBuilderComponent.scala#L603-L638). |
| Oracle | Absent; skipped. | Server-side standard `MERGE`, matching primary keys; the insert branch omits AutoInc columns. [Profile configuration](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/OracleProfile.scala#L64-L76), [shared builder](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcStatementBuilderComponent.scala#L603-L638). |
| PostgreSQL | Absent; skipped. | Transactional server-side `UPDATE ... WHERE primary_key` followed by a conditional `INSERT`. The insert omits AutoInc columns; there is no unique-value lookup. [Profile configuration](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/PostgresProfile.scala#L53-L60), [routing](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/PostgresProfile.scala#L161-L167), [builder](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/PostgresProfile.scala#L225-L245). |
| SQL Server | Absent; skipped. | Server-side standard primary-key `MERGE`, with a terminating semicolon. The insert branch omits AutoInc columns. [Profile configuration](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/SQLServerProfile.scala#L71-L93), [builder specialization](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/SQLServerProfile.scala#L227-L231). |
| SQLite | Absent; skipped. | For an AutoInc table, the composer selects client-side primary-key emulation. Its `INSERT OR REPLACE` builder is not the selected path for this case. [Capability removal](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/SQLiteProfile.scala#L78-L95), [native builder](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/SQLiteProfile.scala#L272-L274), [AutoInc routing](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/SQLiteProfile.scala#L309-L315). |

For the primary-key emulation and `MERGE` paths, repeatedly supplying zero does
not add a unique-value lookup. If no row has primary key zero, the second call
attempts another insert and the repeated unique name causes a constraint error.
This is a source-derived consequence, not a test execution on those engines.
In particular, HSQLDB's Slick-generated DDL explicitly starts its identity at
**1**, as does DB2's shared column builder; Oracle's profile creates a sequence
starting at **1**. These are not assumptions that their database-wide identity
defaults start at one.
[Shared primary-key check/update builders](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcStatementBuilderComponent.scala#L640-L668),
[shared identity DDL](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcStatementBuilderComponent.scala#L841-L846),
[DB2 column builder](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/DB2Profile.scala#L173-L182),
[Oracle sequence and trigger](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/OracleProfile.scala#L284-L292).

MySQL is the alternate-key exception: normally zero requests an automatically
generated ID, and the repeated `name` selects the old row through its unique
index. `NO_AUTO_VALUE_ON_ZERO` makes zero a literal value instead. With several
unique indexes identifying different rows, MySQL can select one matching row;
this is not a primary-key-only guarantee.
[MySQL duplicate-key behavior](https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html),
[zero-value SQL mode](https://dev.mysql.com/doc/refman/8.4/en/sql-mode.html#sqlmode_no_auto_value_on_zero).

For an **existing supplied nonzero ID**, the primary-key paths update that row;
another row's unique value remains a constraint violation. For an **unmatched
AutoInc ID**, their insert branch can generate a different ID because it omits
the AutoInc column. Thus primary-key matching alone does not promise DuckDB's
additional behavior of preserving a supplied ID on insertion.
[Shared native insert branch](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcStatementBuilderComponent.scala#L628-L636),
[standard soft insert omits AutoInc columns](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcActionComponent.scala#L382-L388),
[PostgreSQL insert branch](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/PostgresProfile.scala#L235-L241).

Slick 3.6.1's standard `insertOrUpdateAll` rejects profiles without the
capability; it does not fall back to the single-row client emulation. For these
nine default profiles, only MySQL passes that capability check.
[Bulk action dispatch](https://github.com/slick/slick/blob/v3.6.1/slick/src/main/scala/slick/jdbc/JdbcActionComponent.scala#L528-L536).

## slick-pg 0.23.1

The release's `ExPostgresProfile` inherits Slick's `PostgresProfile`. With its
default capabilities it uses the same PostgreSQL update/conditional-insert
builder, so the inherited test is skipped and ordinary upserts do not search by
an alternate unique value. Applications can explicitly add
`JdbcCapabilities.insertOrUpdate` to enable the native builder.
[Inheritance and selection](https://github.com/tminglei/slick-pg/blob/v0.23.1/core/src/main/scala/com/github/tminglei/slickpg/ExPostgresProfile.scala#L17-L33),
[documented opt-in](https://github.com/tminglei/slick-pg/blob/v0.23.1/README.md#L54-L56).

Native mode generates `ON CONFLICT (primary_key)` and includes the example's
column-level `O.PrimaryKey, O.AutoInc` field in the parameterized insert. It
binds the supplied ID unchanged: **zero is literal primary key zero**, rather
than DuckDB's request to generate an ID. Therefore the exact two-zero test can
pass by inserting ID zero and updating ID zero; that does **not** demonstrate
unique-name matching. This conclusion is inferred from the builder and
PostgreSQL's default-value rules, not a live PostgreSQL execution.
[Native builder and parameter mapping](https://github.com/tminglei/slick-pg/blob/v0.23.1/core/src/main/scala/com/github/tminglei/slickpg/ExPostgresProfile.scala#L112-L136),
[PostgreSQL column defaults](https://www.postgresql.org/docs/17/ddl-default.html).

The native builder also preserves supplied nonzero primary keys on insertion.
slick-pg's own tests distinguish that from default mode, where an unmatched
supplied AutoInc ID becomes a generated ID. Its bulk extension requires native
mode and uses JDBC batching of the native statement.
[Default/native ID assertions](https://github.com/tminglei/slick-pg/blob/v0.23.1/core/src/test/scala/com/github/tminglei/slickpg/PgUpsertSuite.scala#L37-L105),
[bulk extension](https://github.com/tminglei/slick-pg/blob/v0.23.1/core/src/main/scala/com/github/tminglei/slickpg/ExPostgresProfile.scala#L139-L187).

## Consequence for this profile

DuckDB's primary-key-only policy is consistent with the primary-key identity
used by the other implementations above, especially slick-pg's native conflict
target. It deliberately differs from MySQL's handling of arbitrary unique-key
conflicts and from slick-pg native mode's treatment of zero as a literal ID.
Because DuckDB advertises the capability while treating zero as generated, it
must explicitly adapt the inherited test rather than rely on its capability
guard. The [DuckDB override](../src/test/scala/slick/examples/testkit/DuckDBInsertTest.scala#L14)
checks an update by ID and rejection of an independently unique value supplied
with a zero key.
