package utils

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
        // Find the keyword only in SQL code. The original DDL is still used to
        // parse the quoted table name that follows it.
        val code    = maskQuotedTextAndComments(sql)
        val deps    = "(?i)\\breferences\\b".r
          .findAllMatchIn(code)
          .flatMap(mm => refRe.findPrefixMatchOf(sql.substring(mm.start)))
          .map(mm => decodeQualifiedName(mm.group(1)))
          .toSet
        created -> deps
    }
  }

  private def maskQuotedTextAndComments(sql: String): String = {
    val masked = sql.toCharArray
    var i      = 0
    val dollarQuoteRe = "\\$\\$|\\$[A-Za-z_][A-Za-z_0-9]*\\$".r

    while (i < sql.length) {
      val c           = sql.charAt(i)
      val dollarQuote =
        if (c == '$' && (i == 0 || !isIdentifierPart(sql.charAt(i - 1))))
          dollarQuoteRe.findPrefixOf(sql.substring(i))
        else None
      if (dollarQuote.nonEmpty) {
        val delimiter = dollarQuote.get
        val end       = sql.indexOf(delimiter, i + delimiter.length)
        val afterEnd  = if (end < 0) sql.length else end + delimiter.length
        while (i < afterEnd) {
          masked(i) = ' '
          i += 1
        }
      } else if (c == '\'' || c == '"') {
        val quote         = c
        val escapedString = c == '\'' && i > 0 &&
          (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e') &&
          (i == 1 || !isIdentifierPart(sql.charAt(i - 2)))
        masked(i) = ' '
        i += 1
        var closed = false
        while (i < sql.length && !closed) {
          if (escapedString && sql.charAt(i) == '\\' && i + 1 < sql.length) {
            masked(i) = ' '
            masked(i + 1) = ' '
            i += 2
          } else if (sql.charAt(i) == quote) {
            masked(i) = ' '
            if (i + 1 < sql.length && sql.charAt(i + 1) == quote) {
              masked(i + 1) = ' '
              i += 2
            } else {
              i += 1
              closed = true
            }
          } else {
            masked(i) = ' '
            i += 1
          }
        }
      } else if (c == '-' && i + 1 < sql.length && sql.charAt(i + 1) == '-') {
        while (i < sql.length && sql.charAt(i) != '\n') {
          masked(i) = ' '
          i += 1
        }
      } else if (c == '/' && i + 1 < sql.length && sql.charAt(i + 1) == '*') {
        masked(i) = ' '
        masked(i + 1) = ' '
        i += 2
        var depth = 1
        while (i < sql.length && depth > 0) {
          if (i + 1 < sql.length && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
            masked(i) = ' '
            masked(i + 1) = ' '
            depth += 1
            i += 2
          } else if (i + 1 < sql.length && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
            masked(i) = ' '
            masked(i + 1) = ' '
            depth -= 1
            i += 2
          } else {
            masked(i) = ' '
            i += 1
          }
        }
      } else {
        i += 1
      }
    }

    new String(masked)
  }

  private def isIdentifierPart(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '$'

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

  /** Generate a backing sequence name for an auto-increment column.
    *
    * @param tableName
    *   the name of the table
    * @param columnName
    *   the name of the column
    * @return
    *   the sequence name following the convention: tableName_columnName_seq
    */
  def getBackingSequenceName(
      tableName: String,
      columnName: String
  ): String = s"${tableName}_${columnName}_seq"

}
