package components

import slick.ast.ColumnOption.AutoInc
import slick.jdbc.JdbcProfile
import slick.lifted.{ForeignKey, PrimaryKey}
import utils.UtilityFunctions.getBackingSequenceName

trait DuckDBTableDDLBuilderComponent {
  self: JdbcProfile =>

  /** Builder for DDL statements.
    *
    * The implementation here overrides the default Slick behavior of
    * `TableDDLBuilder`, because
    *   1. DuckDB doesn't support `ALTER TABLE` statements for primary and
    *      foreign key constraints. The Slick default behavior is to create the
    *      table and in a separate step altering the table to add constraints;
    *      for DuckDB the key constraints have to be included in the table
    *      creation because there isn't any option to add them later.
    *   2. Auto-increment requires creating a backing sequence manually (as
    *      opposed to `SERIAL` in Postgres)
    */
  class DuckDBTableDDLBuilder(table: Table[?]) extends TableDDLBuilder(table) {

    // Setting the {foreign, primary}Keys to Nil makes overriding the `create*` phases easier.
    // We can simply add a step and delegate back to the parent implementation, e.g., createPhase1.
    override val foreignKeys: Nil.type = Nil
    override val primaryKeys: Nil.type = Nil

    private val autoIncCols = table.create_*.filter(_.options.contains(AutoInc))

    override def createPhase1: Iterable[String] =
      createSequencesBackingAutoIncColumns ++ super.createPhase1

    override def dropPhase2: Iterable[String] =
      super.dropPhase2 ++ dropSequencesBackingAutoIncColumns

    private def createSequencesBackingAutoIncColumns: Iterable[String] =
      autoIncCols.map { col =>
        val seqName = getBackingSequenceName(table.tableName, col.name)
        s"create sequence $seqName"
      }

    private def dropSequencesBackingAutoIncColumns: Iterable[String] =
      autoIncCols.map { col =>
        val seqName = getBackingSequenceName(table.tableName, col.name)
        s"drop sequence $seqName"
      }

    override def createIfNotExistsPhase: Iterable[String] = {
      createSequencesBackingAutoIncColumns ++ super.createIfNotExistsPhase
    }

    override def addTableOptions(sb: StringBuilder): Unit = {
      for (pk <- table.primaryKeys) {
        sb append ","
        addPrimaryKey(pk, sb)
      }
      for (fk <- table.foreignKeys) {
        sb append ","
        addForeignKey(fk, sb)
      }
    }

    override def addPrimaryKey(pk: PrimaryKey, sb: StringBuilder): Unit = {
      sb append "primary key("
      addPrimaryKeyColumnList(pk.columns, sb, tableNode.tableName)
      sb append ")"
    }

    override def addForeignKey(fk: ForeignKey, sb: StringBuilder): Unit = {
      sb append "foreign key("
      addForeignKeyColumnList(
        fk.linearizedSourceColumns,
        sb,
        tableNode.tableName
      )
      sb append ") references " append quoteTableName(fk.targetTable) append "("
      addForeignKeyColumnList(
        fk.linearizedTargetColumnsForOriginalTargetTable,
        sb,
        fk.targetTable.tableName
      )
      sb append ") on update " append fk.onUpdate.action
      sb append " on delete " append fk.onDelete.action
    }
  }

}
