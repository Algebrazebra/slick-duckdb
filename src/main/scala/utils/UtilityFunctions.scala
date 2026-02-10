package utils

import scala.collection.mutable

object UtilityFunctions {

  /** Perform a topological sort so dependencies come first */
  def orderDdlStatements(
      stmts: Iterable[String]
  ): Seq[String] = {
    val (tableDdls, otherDdls) = stmts.partition(isCreateTableStmt)
    val (sequenceDdls, remainingDdls) = otherDdls.partition(isCreateSequenceStmt)

    val parsed = tableDdls.toSeq.map { sql =>
      val (table, deps) = extractForeignKeyDependencies(sql)
      table -> (sql, deps)
    }

    val tableDependencies: Seq[(String, Set[String])] =
      parsed.map { case (table, (_, deps)) => table -> deps }

    val sortedTableNames: Seq[String] = sortTopologically(tableDependencies)

    val nameToSql: Map[String, String] =
      parsed.map { case (table, (sql, _)) => table -> sql }.toMap

    val sortedTableDdls: Seq[String] =
      sortedTableNames.flatMap(nameToSql.get)

    sequenceDdls.toSeq ++ sortedTableDdls ++ remainingDdls.toSeq
  }

  private def extractForeignKeyDependencies(sql: String): (String, Set[String]) = {
    // A double-quoted identifier allowing escaped quotes ("")
    val qIdent = "\"(?:\"\"|[^\"])*\""
    // Qualified name like "schema"."table" (any number of parts)
    val qName  = s"$qIdent(?:\\s*\\.\\s*$qIdent)*"

    // Regexes (case-insensitive, dotall)
    val createRe = ("(?is)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(" + qName + ")").r
    val refRe    = ("(?is)\\breferences\\s+(" + qName + ")").r

    // Decode a qualified name by unquoting each part and un-escaping doubled quotes
    def decodeQualifiedName(qn: String): String = {
      val partRe = ("\"" + "(?:\"\"|[^\"])*" + "\"").r
      partRe.findAllMatchIn(qn).map { m =>
        val s = m.matched
        s.substring(1, s.length - 1).replace("\"\"", "\"")
      }.mkString(".")
    }

    createRe.findFirstMatchIn(sql) match {
      case None => throw new IllegalStateException("Could not extract table name from DDL statement: " + sql)
      case Some(m) =>
        val created = decodeQualifiedName(m.group(1))
        val deps = refRe.findAllMatchIn(sql).map(mm => decodeQualifiedName(mm.group(1))).toSet
        created -> deps
    }
  }

  /** Sort the list of graph node to graph node dependencies topologically using Kahn's algorithm. */
  private def sortTopologically(deps: Seq[(String, Set[String])]): Seq[String] = {

    // Build a set of all nodes
    val allNodes = mutable.Set[String]()
    for ((node, parents) <- deps) {
      allNodes += node
      allNodes ++= parents
    }

    // Build an in-degree map (node -> number of prerequisites)
    val inDegree = mutable.Map[String, Int](allNodes.toSeq.map(_ -> 0)*)

    // Build adjacency list (parent -> children)
    val children = mutable.Map[String, mutable.Set[String]](
      allNodes.toSeq.map(_ -> mutable.Set[String]())*
    )

    // Fill in-degree and adjacency
    for ((node, parents) <- deps) {
      for (parent <- parents) {
        inDegree(node) = inDegree(node) + 1
        children(parent).add(node)
      }
    }

    // Queue of nodes with no remaining prerequisites
    val queue = mutable.Queue[String]()
    for ((n, deg) <- inDegree) {
      if (deg == 0) queue.enqueue(n)
    }

    val result = mutable.ListBuffer[String]()

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

  /** Generate a backing sequence name for an auto-increment column.
    *
    * @param tableName  the name of the table
    * @param columnName the name of the column
    * @return the sequence name following the convention: tableName_columnName_seq
    */
  def getBackingSequenceName(
      tableName: String,
      columnName: String
  ): String = s"${tableName}_${columnName}_seq"

}