package duckdbslick

import components.*
import slick.ast.*
import slick.basic.Capability
import slick.compiler.CompilerState
import slick.dbio.DBIO
import slick.jdbc.JdbcActionComponent.MultipleRowsPerStatementSupport
import slick.jdbc.meta.MTable
import slick.jdbc.{JdbcCapabilities, JdbcProfile}
import slick.relational.RelationalCapabilities

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

/** Slick profile for DuckDB.
  *
  * This profile extends the standard JDBC profile with DuckDB-specific
  * capabilities. It provides a foundation for using Slick with DuckDB
  * databases.
  *
  * DuckDB is an in-process SQL OLAP database management system designed to be
  * fast and efficient for analytical queries.
  */
trait DuckDBProfile
    extends JdbcProfile
    with MultipleRowsPerStatementSupport
    with DuckDBCheckConstraintComponent
    with DuckDBJdbcTypesComponent
    with DuckDBQueryBuilderComponent
    with DuckDBTableDDLBuilderComponent
    with DuckDBColumnDDLBuilderComponent
    with DuckDBUpsertBuilderComponent
    with ReorderingSchemaActionExtensionMethods {

  override def createSchemaActionExtensionMethods(
      schema: DDL
  ): SchemaActionExtensionMethods =
    new ReorderingSchemaActionExtensionMethodsImpl(schema)

  override def defaultTables(implicit
      ec: ExecutionContext
  ): DBIO[Seq[MTable]] = {
    MTable.getTables(None, None, Some("%"), Some(Seq("BASE TABLE")))
  }

  override protected def computeCapabilities: Set[Capability] = {

    val unsupportedCapabilities = Set(
      // Definitely not supported by DuckDB
      JdbcCapabilities.mutable,   // The result type of DuckDB's JDBC driver is immutable
      JdbcCapabilities.forUpdate, // No SELECT ... FOR UPDATE locking in DuckDB
      JdbcCapabilities.nullableNoDefault,

      // Requires missing `DuckDBDatabaseMetaData.getImportedKeys` driver implementation.
      JdbcCapabilities.createModel,

      // Slick queries can be configured to return values such as the insert key using
      // the `returning` method. However, the DuckDB JDBC driver doesn't implement the
      // necessary `prepareStatement` methods on the `DuckDBConnection` class.
      // As a result, `returning` cannot be used with DuckDB at all.
      JdbcCapabilities.returnInsertKey,
      JdbcCapabilities.returnMultipleInsertKey,
      JdbcCapabilities.returnInsertOther,

      // As an embedded database, DuckDB doesn't have a built-in concept of users.
      // Consequently, a `current_user()` function is not provided.
      RelationalCapabilities.functionUser
    )

    // DuckDB supports all capabilities from `slick.sql.SqlCapabilities`; therefore, it's not visible here.
    super.computeCapabilities -- unsupportedCapabilities
  }

  /** DuckDB-specific API.
    *
    * This API extends the standard JDBC API with DuckDB-specific functionality.
    * Future enhancements could include:
    *   - Support for DuckDB-specific data types
    *   - Support for DuckDB-specific SQL functions
    *   - Support for DuckDB-specific query features
    */
  override val api: DuckDBApi = new DuckDBApi
  class DuckDBApi extends JdbcAPI {
    // DuckDB-specific API methods can be added here
  }

  override val columnTypes: DuckDBJdbcTypes = new DuckDBJdbcTypes

  override def createQueryBuilder(
      n: Node,
      state: CompilerState
  ): DuckDBQueryBuilder =
    new DuckDBQueryBuilder(n, state)

  override def createTableDDLBuilder(table: Table[?]): DuckDBTableDDLBuilder =
    new DuckDBTableDDLBuilder(table)

  override def createColumnDDLBuilder(
      column: FieldSymbol,
      table: Table[?]
  ): DuckDBColumnDDLBuilder =
    new DuckDBColumnDDLBuilder(column, table)

  override def createUpsertBuilder(node: Insert): InsertBuilder =
    new DuckDBUpsertBuilder(node)
}

object DuckDBProfile extends DuckDBProfile
