package components

import slick.SlickException
import slick.ast.{ColumnOption, Insert}
import slick.jdbc.{InsertBuilderResult, JdbcProfile}
import utils.UtilityFunctions.getBackingSequenceName

trait DuckDBUpsertBuilderComponent {
  self: JdbcProfile =>

  /** Builder for UPSERT statements.
    *
    * We need to override base UpsertBuilder, because it's implemented using
    * `MERGE` which DuckDB doesn't support in versions <1.4. This implementation
    * uses DuckDB's `INSERT ... ON CONFLICT` syntax instead.
    */
  class DuckDBUpsertBuilder(insert: Insert) extends UpsertBuilder(insert) {
    override def buildInsert: InsertBuilderResult = {
      val hasAutoIncPk  = allFields.exists(f =>
        pkNames.contains(quoteIdentifier(f.name)) &&
          f.options.contains(ColumnOption.AutoInc)
      )
      val uniqueColumns = allFields.collect {
        case f if f.options.contains(ColumnOption.Unique) =>
          quoteIdentifier(f.name)
      }

      val conflictCols = {
        if (hasAutoIncPk && uniqueColumns.nonEmpty) {
          uniqueColumns.mkString(", ")
        } else if (pkNames.nonEmpty) {
          pkNames.mkString(", ")
        } else {
          throw new SlickException(
            "Primary key required for insertOrUpdate"
          )
        }
      }

      val softNamesUpdateAssignments = softNames
        .map(fs => s"$fs = EXCLUDED.$fs")
        .mkString(", ")

      val softNamesUpdateAssignmentsWithOriginalPk = pkNames
        .map(fs => s"$fs = $fs")
        .mkString(", ") + ", " + softNamesUpdateAssignments

      val conflictAction =
        if (softNamesUpdateAssignments.isEmpty) "do nothing"
        else if (hasAutoIncPk && uniqueColumns.nonEmpty)
          "do update set " + softNamesUpdateAssignmentsWithOriginalPk
        else "do update set " + softNamesUpdateAssignments

      val allNamesWithDefault =
        allNames.zip(allFields).map { case (name, field) =>
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
