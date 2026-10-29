package components

import slick.dbio.{Effect, NoStream}
import slick.jdbc.{JdbcBackend, JdbcProfile}
import utils.UtilityFunctions.{orderDdlStatements, orderDropDdlStatements}

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
  self: JdbcProfile =>
  class ReorderingSchemaActionExtensionMethodsImpl(schema: DDL)
      extends JdbcSchemaActionExtensionMethodsImpl(schema: DDL) {

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

    override def create: ProfileAction[Unit, NoStream, Effect.Schema] =
      reorderedAction("schema.create", schema.createStatements.toVector, sql =>
        orderDdlStatements(sql)
      )

    override def createIfNotExists: ProfileAction[Unit, NoStream, Effect.Schema] =
      reorderedAction(
        "schema.createIfNotExists",
        schema.createIfNotExistsStatements.toVector,
        sql => orderDdlStatements(sql)
      )

    private def dropAction(
        name: String,
        statements: Vector[String]
    ): ProfileAction[Unit, NoStream, Effect.Schema] =
      reorderedAction(name, statements, sql =>
        // Explicit replacement statements are already in the caller's order.
        if (sql == statements)
          orderDropDdlStatements(schema.createStatements.toVector, sql)
        else sql
      )

    override def drop: ProfileAction[Unit, NoStream, Effect.Schema] =
      dropAction("schema.drop", schema.dropStatements.toVector)

    override def dropIfExists: ProfileAction[Unit, NoStream, Effect.Schema] =
      dropAction("schema.dropIfExists", schema.dropIfExistsStatements.toVector)
  }
}
