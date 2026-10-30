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

import java.net.JarURLConnection
import scala.concurrent.ExecutionContext
import scala.language.implicitConversions
import scala.util.{Try, control}

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

  // Native ON CONFLICT returns a row for both inserts and updates, whereas Slick
  // requires returning insertOrUpdate to yield None after an update.
  override protected lazy val useServerSideUpsertReturning = false

  private def supportsGeneratedKeys: Boolean =
    try {
      val driverClass = Class.forName(
        "org.duckdb.DuckDBDriver",
        false,
        getClass.getClassLoader
      )
      driverClass.getResource("DuckDBDriver.class").openConnection() match {
        case jar: JarURLConnection =>
          Option(jar.getManifest)
            .flatMap(manifest =>
              Option(manifest.getMainAttributes.getValue("Bundle-Version"))
            )
            .flatMap(version => Try(version.takeWhile(_ != '.').toInt).toOption)
            .exists(_ >= 2)
        case _                     => false
      }
    } catch {
      case control.NonFatal(_) => false
    }

  override def createSchemaActionExtensionMethods(
      schema: DDL
  ): SchemaActionExtensionMethods =
    new ReorderingSchemaActionExtensionMethodsImpl(schema)

  override def defaultTables(implicit
      ec: ExecutionContext
  ): DBIO[Seq[MTable]] = {
    MTable.getTables(None, None, Some("%"), Some(Seq("BASE TABLE", "TABLE")))
  }

  override protected def computeCapabilities: Set[Capability] = {

    val unsupportedCapabilities = Set(
      // Definitely not supported by DuckDB
      JdbcCapabilities.mutable,   // The result type of DuckDB's JDBC driver is immutable
      JdbcCapabilities.forUpdate, // No SELECT ... FOR UPDATE locking in DuckDB
      JdbcCapabilities.nullableNoDefault,

      // Requires missing `DuckDBDatabaseMetaData.getImportedKeys` driver implementation.
      JdbcCapabilities.createModel,

      // As an embedded database, DuckDB doesn't have a built-in concept of users.
      // Consequently, a `current_user()` function is not provided.
      RelationalCapabilities.functionUser
    )

    // Slick queries can be configured to return values such as the insert key using
    // the `returning` method. However, the DuckDB JDBC driver only implemented the necessary
    // capabilities in version 2.0.0.0.
    val returningCapabilities                 = Set(
      JdbcCapabilities.returnInsertKey,
      JdbcCapabilities.returnMultipleInsertKey,
      JdbcCapabilities.returnInsertOther
    )
    val versionDependentReturningCapabilities = {
      if (supportsGeneratedKeys) Set.empty[Capability]
      else returningCapabilities
    }

    // DuckDB supports all capabilities from `slick.sql.SqlCapabilities`; therefore, it's not visible here.
    super.computeCapabilities -- unsupportedCapabilities -- versionDependentReturningCapabilities
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
