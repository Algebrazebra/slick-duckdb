package components

import slick.dbio.{Effect, NoStream}
import slick.jdbc.{JdbcBackend, JdbcProfile}
import utils.UtilityFunctions.orderDdlStatements

/** Provides extension methods for schema manipulation in a JDBC profile.
  *
  * This trait introduces functionality to reorder DDL statements during schema
  * creation, ensuring that dependencies such as foreign keys or sequences are
  * resolved correctly. This is necessary in DuckDB because adding foreign key
  * constraints must be done on table creation. Other things such as creating
  * the sequences backing auto-increment columns also require a specific
  * ordering.
  *
  * The reordering is done via a topological sort, which places dependent
  * entities in the correct order to prevent execution errors during schema
  * creation.
  */
trait ReorderingSchemaActionExtensionMethods {
  self: JdbcProfile =>
  class ReorderingSchemaActionExtensionMethodsImpl(schema: DDL)
      extends JdbcSchemaActionExtensionMethodsImpl(schema: DDL) {

    override def create: ProfileAction[Unit, NoStream, Effect.Schema] =
      new SimpleJdbcProfileAction[Unit](
        "schema.create",
        schema.createStatements.toVector
      ) {
        def run(
            ctx: JdbcBackend#JdbcActionContext,
            sql: Vector[String]
        ): Unit = {
          val reorderedSql = orderDdlStatements(sql)
          for (s <- reorderedSql)
            ctx.session.withPreparedStatement(s)(_.execute)
        }
      }
  }
}
