package duckdbslick

import duckdbslick.components.*
import duckdbslick.types.{Json, JsonPath, TimestampTzNs}
import slick.ast.*
import slick.ast.Library.SqlFunction
import slick.basic.Capability
import slick.compiler.CompilerState
import slick.dbio.DBIO
import slick.jdbc.JdbcActionComponent.MultipleRowsPerStatementSupport
import slick.jdbc.meta.{MColumn, MTable}
import slick.jdbc.{JdbcCapabilities, JdbcModelBuilder, JdbcProfile}
import slick.lifted.ExtensionMethods
import slick.relational.RelationalCapabilities
import utils.UtilityFunctions.getBackingSequenceName

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
    with DuckDBJsonFunctionsComponent
    with DuckDBQueryBuilderComponent
    with DuckDBTableDDLBuilderComponent
    with DuckDBColumnDDLBuilderComponent
    with DuckDBUpsertBuilderComponent
    with ReorderingSchemaActionExtensionMethods {

  // Native ON CONFLICT returns a row for both inserts and updates, whereas Slick
  // requires returning insertOrUpdate to yield None after an update.
  override protected lazy val useServerSideUpsertReturning = false

  override def createSchemaActionExtensionMethods(
      schema: DDL
  ): SchemaActionExtensionMethods =
    new ReorderingSchemaActionExtensionMethodsImpl(schema)

  override def defaultTables(implicit
      ec: ExecutionContext
  ): DBIO[Seq[MTable]] = {
    MTable.getTables(None, None, Some("%"), Some(Seq("BASE TABLE", "TABLE")))
  }

  override def createModelBuilder(tables: Seq[MTable], ignoreInvalidDefaults: Boolean)(implicit
      ec: ExecutionContext
  ): JdbcModelBuilder = new JdbcModelBuilder(tables, ignoreInvalidDefaults) {
    override def createColumnBuilder(tableBuilder: TableBuilder, meta: MColumn): ColumnBuilder =
      new ColumnBuilder(tableBuilder, meta) {
        private def hasBackingSequenceDefault: Boolean = {
          val sequenceName = getBackingSequenceName(tableBuilder.namer.name, meta.name)
          super.rawDefault.exists(_.equalsIgnoreCase(s"nextval('$sequenceName')"))
        }

        override def autoInc: Boolean = super.autoInc || hasBackingSequenceDefault

        override def rawDefault: Option[String] =
          // JDBC 1.5.2 reports CAST('t' AS BOOLEAN); 2.0 reports true.
          if (tpe == "Boolean") super.rawDefault.map {
            case value
                if value.equalsIgnoreCase("true") ||
                  value.equalsIgnoreCase("CAST('t' AS BOOLEAN)") => "TRUE"
            case value
                if value.equalsIgnoreCase("false") ||
                  value.equalsIgnoreCase("CAST('f' AS BOOLEAN)") => "FALSE"
            case value => value
          }
          else super.rawDefault
      }
  }

  override protected def computeCapabilities: Set[Capability] = {

    val unsupportedCapabilities = Set(
      // Definitely not supported by DuckDB
      JdbcCapabilities.mutable,   // The result type of DuckDB's JDBC driver is immutable
      JdbcCapabilities.forUpdate, // No SELECT ... FOR UPDATE locking in DuckDB

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
      if (DuckDBVersion.supportsGeneratedKeys(getClass.getClassLoader))
        Set.empty[Capability]
      else returningCapabilities
    }

    // Slick's model builder calls getImportedKeys for every table. DuckDB JDBC
    // implemented that metadata method in 1.5.2.0.
    val versionDependentModelCapabilities =
      if (DuckDBVersion.supportsCreateModel(getClass.getClassLoader))
        Set.empty[Capability]
      else Set(JdbcCapabilities.createModel)

    // DuckDB supports all capabilities from `slick.sql.SqlCapabilities`; therefore, it's not visible here.
    super.computeCapabilities -- unsupportedCapabilities -- versionDependentReturningCapabilities -- versionDependentModelCapabilities
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
  class DuckDBApi extends JdbcAPI with JsonApi {
    implicit lazy val timestampTzNsJdbcType: BaseColumnType[TimestampTzNs] =
      columnTypes.timestampTzNsJdbcType

    implicit lazy val jsonJdbcType: BaseColumnType[Json] =
      columnTypes.jsonJdbcType

    implicit lazy val jsonPathJdbcType: BaseColumnType[JsonPath] =
      MappedColumnType.base[JsonPath, String](_.value, JsonPath(_))

    private val jsonSetFunction = new SqlFunction("json_set")
    private val jsonInsertFunction = new SqlFunction("json_insert")
    private val jsonReplaceFunction = new SqlFunction("json_replace")
    private val jsonRemoveFunction = new SqlFunction("json_remove")

    implicit def jsonColumnExtensionMethods(
        c: Rep[Json]
    ): JsonColumnExtensionMethods[Json] = new JsonColumnExtensionMethods(c)

    implicit def jsonOptionColumnExtensionMethods(
        c: Rep[Option[Json]]
    ): JsonColumnExtensionMethods[Option[Json]] = new JsonColumnExtensionMethods(c)

    class JsonColumnExtensionMethods[P1](val c: Rep[P1])
        extends ExtensionMethods[Json, P1] {
      protected implicit def b1Type: TypedType[Json] = jsonJdbcType

      def jsonSet[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
          om: o#arg[JsonPath, P2]#arg[Json, P3]#to[Json, R]
      ): Rep[R] = {
        DuckDBVersion.requireJsonMutations(DuckDBProfile.this.getClass.getClassLoader)
        om.column(jsonSetFunction, n, path.toNode, value.toNode)
      }

      def jsonInsert[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
          om: o#arg[JsonPath, P2]#arg[Json, P3]#to[Json, R]
      ): Rep[R] = {
        DuckDBVersion.requireJsonMutations(DuckDBProfile.this.getClass.getClassLoader)
        om.column(jsonInsertFunction, n, path.toNode, value.toNode)
      }

      def jsonReplace[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
          om: o#arg[JsonPath, P2]#arg[Json, P3]#to[Json, R]
      ): Rep[R] = {
        DuckDBVersion.requireJsonMutations(DuckDBProfile.this.getClass.getClassLoader)
        om.column(jsonReplaceFunction, n, path.toNode, value.toNode)
      }

      def jsonRemove[P2, R](path: Rep[P2])(implicit
          om: o#arg[JsonPath, P2]#to[Json, R]
      ): Rep[Option[Json]] = {
        DuckDBVersion.requireJsonMutations(DuckDBProfile.this.getClass.getClassLoader)
        // Removing the root path returns SQL NULL even for non-null inputs.
        Rep.forNode[Option[Json]](
          jsonRemoveFunction.typed(jsonJdbcType.optionType, n, path.toNode)
        )(jsonJdbcType.optionType)
      }
    }
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
