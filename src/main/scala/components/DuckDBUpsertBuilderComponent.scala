package components

import components.DuckDBColumnDDLBuilderComponent.getBackingSequenceName
import slick.SlickException
import slick.ast.{ColumnOption, FieldSymbol, Insert}
import slick.jdbc.{InsertBuilderResult, JdbcProfile}

trait DuckDBUpsertBuilderComponent {
  self: JdbcProfile =>

  /** Builder for UPSERT statements.
    *
    * We need to override base UpsertBuilder, because it's implemented using
    * `MERGE` which DuckDB doesn't support. This implementation uses DuckDB's
    * `INSERT ... ON CONFLICT` syntax instead.
    */
  class DuckDBUpsertBuilder(insert: Insert) extends UpsertBuilder(insert) {
    override def buildInsert: InsertBuilderResult = {
      val hasAutoInc = allFields.exists(_.options.contains(ColumnOption.AutoInc))

      val conflictCols = {
        val uniqueColumns = allFields.collect {
          case f if f.options.contains(ColumnOption.Unique) =>
            quoteIdentifier(f.name)
        }
        val pkCols = pkNames

        if (hasAutoInc && uniqueColumns.nonEmpty) {
          uniqueColumns.mkString(", ")
        } else if (pkCols.nonEmpty) {
          pkCols.mkString(", ")
        } else if (uniqueColumns.nonEmpty) {
          uniqueColumns.mkString(", ")
        } else {
          throw new SlickException(
            "Primary key or unique column required for insertOrUpdate"
          )
        }
      }

      val updateAssignments = softNames
        .map(fs => s"$fs = EXCLUDED.$fs")
        .mkString(", ")
      val conflictAction =
        if (updateAssignments.isEmpty) "do nothing"
        else "do update set " + updateAssignments

      val allNamesWithDefault = allNames.zip(allFields).map {
        case (name, field) =>
          if (field.options.contains(ColumnOption.AutoInc)) {
            val seqName = getBackingSequenceName(table.tableName, field.name)
            s"case when $name = 0 then nextval('$seqName') else $name end"
          } else name
      }

      val insertSql =
        s"""insert into $tableName (${allNames.mkString(", ")})
           |select ${allNamesWithDefault.mkString(", ")}
           |from (values $allVars) t(${allNames.mkString(", ")})
           |on conflict ($conflictCols)
           |$conflictAction
           |""".stripMargin.replaceAll("\n", " ")

      new InsertBuilderResult(table, insertSql, allFields) {
        override def buildMultiRowInsert(size: Int): String = {
          val multiRowPlaceholder = List.fill(size)(allVars).mkString(", ")

          s"""insert into $tableName (${allNames.mkString(", ")})
             |select ${allNamesWithDefault.mkString(", ")}
             |from (values $multiRowPlaceholder) t(${allNames.mkString(", ")})
             |on conflict ($conflictCols)
             |$conflictAction
             |""".stripMargin.replaceAll("\n", " ")
        }
      }
    }
  }
}
