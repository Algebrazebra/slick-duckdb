package components

import slick.jdbc.JdbcProfile
import utils.UtilityFunctions.TableDefinition

/** Carries table relationships alongside Slick DDL through schema composition. */
trait DuckDBSchemaMetadata {
  self: JdbcProfile =>

  trait MetadataDDL extends DDL {
    def underlying: DDL
    def tableDefinitions: Vector[TableDefinition]

    override def createStatements: Iterator[String] = underlying.createStatements
    override def createIfNotExistsStatements: Iterator[String] =
      underlying.createIfNotExistsStatements
    override def dropStatements: Iterator[String] = underlying.dropStatements
    override def dropIfExistsStatements: Iterator[String] =
      underlying.dropIfExistsStatements
    override def truncateStatements: Iterator[String] = underlying.truncateStatements

    // Keep statements available if an ordinary Slick DDL is on the left of ++.
    // MetadataDDL's own ++ uses the underlying DDL to retain the exact phases.
    protected def createPhase1: Iterable[String] =
      underlying.createStatements.toVector
    protected def createPhase2: Iterable[String] = Nil
    protected def createIfNotExistsPhase: Iterable[String] =
      underlying.createIfNotExistsStatements.toVector
    protected def dropPhase1: Iterable[String] = Nil
    protected def dropIfExistsPhase: Iterable[String] =
      underlying.dropIfExistsStatements.toVector
    protected def dropPhase2: Iterable[String] = underlying.dropStatements.toVector
    protected def truncatePhase: Iterable[String] =
      underlying.truncateStatements.toVector

    override def ++(other: DDL): DDL = {
      val (otherDdl, otherTables) = other match {
        case m: MetadataDDL => m.underlying -> m.tableDefinitions
        case ddl            => ddl -> Vector.empty[TableDefinition]
      }
      withMetadata(underlying ++ otherDdl, tableDefinitions ++ otherTables)
    }

  }

  def withMetadata(ddl: DDL, tables: Vector[TableDefinition]): DDL =
    new MetadataDDL {
      val underlying: DDL = ddl
      val tableDefinitions: Vector[TableDefinition] = tables
    }
}
