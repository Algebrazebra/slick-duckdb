package utils

import java.nio.charset.StandardCharsets
import java.util.Locale
import scala.collection.mutable

object UtilityFunctions {

  private type QualifiedName = Vector[String]

  /** Perform a topological sort so dependencies come first */
  def orderDdlStatements(
      stmts: Iterable[String]
  ): Seq[String] = {
    val (tableDdls, otherDdls)        = stmts.partition(isCreateTableStmt)
    val (sequenceDdls, remainingDdls) =
      otherDdls.partition(isCreateSequenceStmt)

    val parsed = tableDdls.toSeq.map { sql =>
      val (table, deps) = extractForeignKeyDependencies(sql)
      table -> (sql, deps)
    }

    val tableDependencies: Seq[(QualifiedName, Set[QualifiedName])] =
      parsed.map { case (table, (_, deps)) => table -> deps }

    val sortedTableNames: Seq[QualifiedName] =
      sortTopologically(tableDependencies)

    val nameToSql: Map[QualifiedName, String] =
      parsed.map { case (table, (sql, _)) => table -> sql }.toMap

    val sortedTableDdls: Seq[String] =
      sortedTableNames.flatMap(nameToSql.get)

    sequenceDdls.toSeq ++ sortedTableDdls ++ remainingDdls.toSeq
  }

  private def extractForeignKeyDependencies(
      sql: String
  ): (QualifiedName, Set[QualifiedName]) = {
    // A double-quoted identifier allowing escaped quotes ("")
    val qIdent = "\"(?:\"\"|[^\"])*\""
    // Qualified name like "schema"."table" (any number of parts)
    val qName  = s"$qIdent(?:\\s*\\.\\s*$qIdent)*"

    // Regexes (case-insensitive, dotall)
    val createRe =
      ("(?is)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(" + qName + ")").r
    val refRe    = ("(?is)\\breferences\\s+(" + qName + ")").r

    // Decode a qualified name by unquoting each part and un-escaping doubled quotes
    def decodeQualifiedName(qn: String): QualifiedName = {
      val partRe = ("\"" + "(?:\"\"|[^\"])*" + "\"").r
      partRe
        .findAllMatchIn(qn)
        .map { m =>
          val s = m.matched
          s.substring(1, s.length - 1).replace("\"\"", "\"")
        }
        .toVector
    }

    createRe.findFirstMatchIn(sql) match {
      case None    =>
        throw new IllegalStateException(
          "Could not extract table name from DDL statement: " + sql
        )
      case Some(m) =>
        val created = decodeQualifiedName(m.group(1))
        val deps    = refRe
          .findAllMatchIn(sql)
          .map(mm => decodeQualifiedName(mm.group(1)))
          .toSet
        created -> deps
    }
  }

  /** Sort the list of graph node to graph node dependencies topologically using
    * Kahn's algorithm.
    */
  private def sortTopologically(
      deps: Seq[(QualifiedName, Set[QualifiedName])]
  ): Seq[QualifiedName] = {

    // Build a set of all nodes
    val allNodes = mutable.Set[QualifiedName]()
    for ((node, parents) <- deps) {
      allNodes += node
      allNodes ++= parents
    }

    // Build an in-degree map (node -> number of prerequisites)
    val inDegree = mutable.Map[QualifiedName, Int](allNodes.toSeq.map(_ -> 0)*)

    // Build adjacency list (parent -> children)
    val children = mutable.Map[QualifiedName, mutable.Set[QualifiedName]](
      allNodes.toSeq.map(_ -> mutable.Set[QualifiedName]())*
    )

    // Fill in-degree and adjacency
    for ((node, parents) <- deps) {
      for (parent <- parents) {
        inDegree(node) = inDegree(node) + 1
        children(parent).add(node)
      }
    }

    // Queue of nodes with no remaining prerequisites
    val queue = mutable.Queue[QualifiedName]()
    for ((n, deg) <- inDegree) {
      if (deg == 0) queue.enqueue(n)
    }

    val result = mutable.ListBuffer[QualifiedName]()

    while (queue.nonEmpty) {
      val n = queue.dequeue()
      result += n

      for (child <- children(n)) {
        inDegree(child) = inDegree(child) - 1
        if (inDegree(child) == 0) queue.enqueue(child)
      }
    }

    if (result.size != allNodes.size) {
      throw new IllegalArgumentException("Cyclic dependency detected.")
    }

    result.toSeq
  }

  private def isCreateTableStmt(sql: String): Boolean =
    sql.toLowerCase.startsWith("create table")

  private def isCreateSequenceStmt(sql: String): Boolean =
    sql.toLowerCase.startsWith("create sequence")

  final case class BackingSequenceName(schema: Option[String], name: String) {
    def sqlIdentifier(quoteIdentifier: String => String): String =
      (schema.toSeq :+ name).map(quoteIdentifier).mkString(".")

    def nextvalArgument(quoteIdentifier: String => String): String =
      "'" + sqlIdentifier(quoteIdentifier).replace("'", "''") + "'"
  }

  private val encodedSequencePrefix = "__slick_duckdb_seq_"

  private def hex(value: String): String =
    value.getBytes(StandardCharsets.UTF_8).iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def needsEncoding(value: String): Boolean =
    value.exists(c => c == '"' || c == '.')

  /** Generate the qualified backing sequence name for an auto-increment column.
    * Older DuckDB nextval parsers cannot resolve a sequence or schema name
    * containing a double quote or dot. Encode affected sequence names, and
    * keep sequences for affected schemas in main with the schema encoded in
    * their names. Ordinary names retain their existing convention.
    */
  def getBackingSequenceName(
      schemaName: Option[String],
      tableName: String,
      columnName: String
  ): BackingSequenceName = {
    val ordinaryName = s"${tableName}_${columnName}_seq"
    schemaName match {
      case Some(schema) if needsEncoding(schema) =>
        BackingSequenceName(
          Some("main"),
          s"$encodedSequencePrefix${hex(schema)}_${hex(tableName)}_${hex(columnName)}"
        )
      case _ =>
        val name =
          if (
            needsEncoding(tableName) || needsEncoding(columnName) ||
            ordinaryName.toLowerCase(Locale.ROOT).startsWith(encodedSequencePrefix)
          ) s"$encodedSequencePrefix${hex(tableName)}_${hex(columnName)}"
          else ordinaryName
        BackingSequenceName(schemaName, name)
    }
  }

}
