package components

import slick.ast.FieldSymbol
import slick.jdbc.JdbcProfile
import utils.UtilityFunctions.getBackingSequenceName

trait DuckDBColumnDDLBuilderComponent {
  self: JdbcProfile =>

  /** Builder for the column definition parts of DDL statements.
    *
    * Customizes how column options are appended to the SQL statement,
    * particularly for auto-increment columns which require a backing sequence.
    */
  class DuckDBColumnDDLBuilder(column: FieldSymbol, table: Table[?])
      extends ColumnDDLBuilder(column) {

    private lazy val backingSequenceName: String =
      getBackingSequenceName(table.tableName, column.name)

    override protected def appendOptions(sb: StringBuilder): Unit = {
      if (autoIncrement)
        sb append " DEFAULT " append "nextval('" append backingSequenceName append "')"
      if (defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if (notNull) sb append " NOT NULL"
      if (primaryKey) sb append " PRIMARY KEY"
      if (unique) sb append " UNIQUE"
    }
  }
}
