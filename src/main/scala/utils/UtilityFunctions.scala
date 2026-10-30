package utils

import scala.collection.mutable

object UtilityFunctions {

  final case class TableId(schema: Option[String], name: String)

  final case class TableDefinition(
      id: TableId,
      dependencies: Set[TableId],
      create: String,
      createIfNotExists: String,
      drop: String,
      dropIfExists: String
  )

  /** Reorder generated table statements using Slick's foreign-key metadata. */
  def orderCreateDdlStatements(
      stmts: Iterable[String],
      tables: Seq[TableDefinition],
      ifNotExists: Boolean
  ): Seq[String] = {
    def createSql(table: TableDefinition): String =
      if (ifNotExists) table.createIfNotExists else table.create

    val tableSql = tables.map(createSql).toSet
    val (presentTables, otherDdls) = stmts.partition(tableSql.contains)
    if (presentTables.size != tables.size)
      throw new IllegalStateException("Could not match every table creation statement")

    val (sequenceDdls, remainingDdls) =
      otherDdls.partition(isCreateSequenceStmt)
    val nameToSql = tables.map(table => table.id -> createSql(table)).toMap
    val sortedTables = sortTopologically(tableDependencies(tables))
      .flatMap(nameToSql.get)

    sequenceDdls.toSeq ++ sortedTables ++ remainingDdls.toSeq
  }

  /** Drop dependent tables before the tables they reference. */
  def orderDropDdlStatements(
      stmts: Iterable[String],
      tables: Seq[TableDefinition],
      ifExists: Boolean
  ): Seq[String] = {
    def dropSql(table: TableDefinition): String =
      if (ifExists) table.dropIfExists else table.drop

    val tableSql = tables.map(dropSql).toSet
    val (presentTables, otherDdls) = stmts.partition(tableSql.contains)
    if (presentTables.size != tables.size)
      throw new IllegalStateException("Could not match every table drop statement")

    val nameToSql = tables.map(table => table.id -> dropSql(table)).toMap
    val sortedTables = sortTopologically(tableDependencies(tables))
      .reverse
      .flatMap(nameToSql.get)

    sortedTables ++ otherDdls
  }

  private def tableDependencies(
      tables: Seq[TableDefinition]
  ): Seq[(TableId, Set[TableId])] =
    tables.map(table => table.id -> (table.dependencies - table.id))

  /** Sort the list of graph node to graph node dependencies topologically using
    * Kahn's algorithm.
    */
  private def sortTopologically(
      deps: Seq[(TableId, Set[TableId])]
  ): Seq[TableId] = {

    // Build a set of all nodes
    val allNodes = mutable.Set[TableId]()
    for ((node, parents) <- deps) {
      allNodes += node
      allNodes ++= parents
    }

    // Build an in-degree map (node -> number of prerequisites)
    val inDegree = mutable.Map[TableId, Int](allNodes.toSeq.map(_ -> 0)*)

    // Build adjacency list (parent -> children)
    val children = mutable.Map[TableId, mutable.Set[TableId]](
      allNodes.toSeq.map(_ -> mutable.Set[TableId]())*
    )

    // Fill in-degree and adjacency
    for ((node, parents) <- deps) {
      for (parent <- parents) {
        inDegree(node) = inDegree(node) + 1
        children(parent).add(node)
      }
    }

    // Queue of nodes with no remaining prerequisites
    val queue = mutable.Queue[TableId]()
    for ((n, deg) <- inDegree) {
      if (deg == 0) queue.enqueue(n)
    }

    val result = mutable.ListBuffer[TableId]()

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
