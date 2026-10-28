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
      val uniqueColumns = allFields.collect {
        case f if f.options.contains(ColumnOption.Unique) =>
          quoteIdentifier(f.name)
      }

      val conflictCols = {
        if (pkNames.nonEmpty) {
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

      val conflictAction =
        if (softNamesUpdateAssignments.isEmpty) "do nothing"
        else "do update set " + softNamesUpdateAssignments

      // Only a zero, single-column generated key may identify an existing row
      // by a unique value. Explicit keys always determine the conflict target.
      val lookupByUniqueValue =
        pkSyms.size == 1 && pkSyms.head.options.contains(ColumnOption.AutoInc) &&
          uniqueColumns.length == 1

      val allNamesWithDefault =
        allNames.zip(allFields).map { case (name, field) =>
          if (field.options.contains(ColumnOption.AutoInc)) {
            val seqName      = getBackingSequenceName(table.tableName, field.name)
            val generatedKey =
              if (lookupByUniqueValue && pkNames.contains(name)) {
                val uniqueColumn = uniqueColumns.head
                s"coalesce((select existing.$name from $tableName existing " +
                  s"where existing.$uniqueColumn = t.$uniqueColumn), nextval('$seqName'))"
              } else s"nextval('$seqName')"
            s"case when t.$name = 0 then $generatedKey else t.$name end"
          } else s"t.$name"
        }

      // Resolve defaults once per input row, outside correlated subqueries, so
      // repeated input values still receive independent sequence values.
      val originalKeyFields =
        if (lookupByUniqueValue) Seq(s"t.${pkNames.head} as original_key")
        else Seq.empty
      val sourceFields = allNamesWithDefault.zipWithIndex
        .map { case (value, index) => s"$value as c$index" }
        .toSeq ++ originalKeyFields

      val selectedFields = allNames.zipWithIndex.map { case (name, index) =>
        val value = s"resolved.c$index"
        if (lookupByUniqueValue && pkNames.contains(name)) {
          val uniqueColumn = uniqueColumns.head
          val uniqueIndex  = allNames.toSeq.indexOf(uniqueColumn)
          // A sequence may lag behind explicitly inserted keys. Such a
          // collision must fail instead of updating an unrelated row.
          s"""case when resolved.original_key = 0 and exists
             |(select 1 from $tableName occupied where occupied.$name = $value
             |and (occupied.$uniqueColumn = resolved.c$uniqueIndex) is not true)
             |then error('Generated primary key conflicts with an existing row')
             |else $value end""".stripMargin
        } else value
      }

      def buildUpsert(placeholders: String): String =
        s"""insert into $tableName (${allNames.mkString(", ")})
           |select ${selectedFields.mkString(", ")}
           |from (select ${sourceFields.mkString(", ")}
           |from (values $placeholders) t(${allNames.mkString(", ")})) resolved
           |on conflict ($conflictCols)
           |$conflictAction
           |""".stripMargin.replaceAll("\n", " ")

      new InsertBuilderResult(table, buildUpsert(allVars), allFields) {
        override def buildMultiRowInsert(size: Int): String = {
          val multiRowPlaceholder = List.fill(size)(allVars).mkString(", ")
          buildUpsert(multiRowPlaceholder)
        }
      }
    }
  }
}
