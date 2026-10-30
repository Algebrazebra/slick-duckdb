package components

import slick.dbio.{Effect, NoStream}
import slick.jdbc.{JdbcBackend, JdbcProfile}
import utils.UtilityFunctions.{TableDefinition, orderCreateDdlStatements, orderDropDdlStatements}

/** Provides extension methods for schema manipulation in a JDBC profile.
  *
  * This trait reorders schema DDL for creation and deletion so dependencies
  * such as foreign keys and sequences are handled in the right order. DuckDB
  * requires foreign keys to be added on table creation, and auto-increment
  * columns require backing sequences to exist first.
  *
  * The reordering is done via a topological sort, which places dependent
  * entities in the correct order to prevent execution errors during schema
  * creation and deletion.
  */
trait ReorderingSchemaActionExtensionMethods {
  self: JdbcProfile with DuckDBSchemaMetadata =>
  class ReorderingSchemaActionExtensionMethodsImpl(schema: DDL)
      extends JdbcSchemaActionExtensionMethodsImpl(schema: DDL) {

    private val tables: Vector[TableDefinition] = schema match {
      case metadata: MetadataDDL => metadata.tableDefinitions
      case _                     => Vector.empty
    }

    private def reorderedAction(
        name: String,
        statements: Vector[String],
        reorder: Vector[String] => Seq[String]
    ): ProfileAction[Unit, NoStream, Effect.Schema] =
      new SimpleJdbcProfileAction[Unit](name, statements) {
        def run(
            ctx: JdbcBackend#JdbcActionContext,
            sql: Vector[String]
        ): Unit = {
          for (s <- reorder(sql))
            ctx.session.withPreparedStatement(s)(_.execute)
        }
      }

    override def create: ProfileAction[Unit, NoStream, Effect.Schema] = {
      val statements = schema.createStatements.toVector
      reorderedAction("schema.create", statements, sql =>
        if (sql == statements) orderCreateDdlStatements(sql, tables, false)
        else sql
      )
    }

    override def createIfNotExists: ProfileAction[Unit, NoStream, Effect.Schema] = {
      val statements = schema.createIfNotExistsStatements.toVector
      reorderedAction(
        "schema.createIfNotExists",
        statements,
        sql =>
          if (sql == statements) orderCreateDdlStatements(sql, tables, true)
          else sql
      )
    }

    private def dropAction(
        name: String,
        statements: Vector[String]
    ): ProfileAction[Unit, NoStream, Effect.Schema] =
      reorderedAction(name, statements, sql =>
        // Explicit replacement statements are already in the caller's order.
        if (sql == statements)
          orderDropDdlStatements(sql, tables, name == "schema.dropIfExists")
        else sql
      )

    override def drop: ProfileAction[Unit, NoStream, Effect.Schema] =
      dropAction("schema.drop", schema.dropStatements.toVector)

    override def dropIfExists: ProfileAction[Unit, NoStream, Effect.Schema] =
      dropAction("schema.dropIfExists", schema.dropIfExistsStatements.toVector)
  }
}
