package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.{Json, JsonPath, JsonTableRow}
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test
import slick.jdbc.GetResult

import java.sql.DriverManager

class DuckDBJsonTableFunctionTest {
  @Test def jdbcProjectionHasElevenColumnsAndExactUnsignedText(): Unit = {
    Class.forName("org.duckdb.DuckDBDriver")
    val connection = DriverManager.getConnection("jdbc:duckdb:")
    try {
      val statement = connection.prepareStatement(
        "SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent, " +
          "jt.fullkey, jt.path, jt.json, jt.root, jt.rowid " +
          "FROM json_tree(?) AS jt")
      try {
        statement.setString(1, "{\"n\":null}")
        val result = statement.executeQuery()
        try {
          val metadata = result.getMetaData
          assertEquals(11, metadata.getColumnCount)
          assertEquals(Seq("key", "value", "type", "atom", "id", "parent",
            "fullkey", "path", "json", "root", "rowid"),
            (1 to 11).map(metadata.getColumnName))
          assertEquals("UBIGINT", metadata.getColumnTypeName(5))
          assertEquals("UBIGINT", metadata.getColumnTypeName(6))
          assertTrue(result.next())
          assertEquals(null, result.getString(4))
          assertTrue(result.wasNull())
          assertEquals(BigInt(result.getString(5)).toString, result.getObject(5).toString)
          assertFalse(result.wasNull())
        } finally result.close()
      } finally statement.close()
    } finally connection.close()
  }

  @Test def eachReturnsImmediateChildrenWithExplicitVirtualColumns(): Unit = {
    val document = Json("{\"array\":[1,2],\"missing\":null}")
    val action = sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                         jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                  FROM json_each($document) AS jt""".as[JsonTableRow[Json]]
    assertTrue(action.statements.head.contains("json_each(?)"))
    val rows = run(action)
    assertEquals(2, rows.size)
    assertEquals(Set(Some("array"), Some("missing")), rows.map(_.key).toSet)
    val array = rows.find(_.key.contains("array")).get
    val missing = rows.find(_.key.contains("missing")).get
    val jsonValue: Json = array.value
    val jsonAtom: Option[Json] = array.atom
    val unsignedId: BigInt = array.id
    val unsignedParent: Option[BigInt] = array.parent
    val rowId: Long = array.rowId
    assertTrue(unsignedId >= 0)
    assertTrue(rowId >= 0)
    assertEquals(None, unsignedParent)
    assertEquals(Json("[1,2]"), jsonValue)
    assertEquals(None, jsonAtom)
    assertEquals(Json.nullValue, missing.value)
    assertEquals(None, missing.atom)
    assertEquals(None, array.parent)
    assertEquals(document, array.inputJson)
    assertEquals(Some("$"), array.root)
    assertEquals("$.array", array.fullKey)
    assertEquals("$", array.path)
  }

  @Test def treeIncludesRootAndDescendantsForBoundPaths(): Unit = {
    val document = Json("{\"array\":[1,null],\"other\":true}")
    for (path <- Seq(JsonPath("$.array"), JsonPath("/array"))) {
      val tablePath = jsonTablePath(path)
      val action = sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                           jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                    FROM json_tree($document, $tablePath) AS jt""".as[JsonTableRow[Json]]
      assertTrue(action.statements.head.contains("json_tree(?, ?)"))
      val rows = run(action)
      assertEquals(3, rows.size)
      val root = rows.find(_.parent.isEmpty).get
      assertEquals(Json("[1,null]"), root.value)
      assertEquals(Some(if (path.value.startsWith("/")) "$.\"array\"" else "$.array"), root.root)
      assertEquals(Set(root.id), rows.flatMap(_.parent).toSet)
      assertEquals(Set(Json("1"), Json.nullValue), rows.filter(_.parent.nonEmpty).map(_.value).toSet)
    }
  }

  @Test def pointerEscapesBindAsJsonPath(): Unit = {
    val document = Json("{\"a/b\":{\"~key\":4}}")
    val path = jsonTablePath(JsonPath("/a~1b/~0key"))
    val action = sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                         jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                  FROM json_tree($document, $path) AS jt""".as[JsonTableRow[Json]]
    assertTrue(action.statements.head.contains("json_tree(?, ?)"))
    val rows = run(action)
    assertEquals(1, rows.size)
    assertEquals(Json.number(4), rows.head.value)
    assertEquals(Some("$.\"a/b\".\"~key\""), rows.head.root)
  }

  @Test def numericPointerKeepsObjectKeyMeaning(): Unit = {
    val document = Json("{\"0\":7}")
    val pointer = JsonPath("/0")
    assertEquals("7", run(sql"select json_extract(cast($document as json), $pointer)".as[String].head))
    assertTrue(scala.util.Try(jsonTablePath(pointer)).failed.get.getMessage
      .contains("Numeric JSON Pointer tokens"))
    val objectKeyPath = JsonPath("$.\"0\"")
    val rows = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                                 jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                          FROM json_tree($document, $objectKeyPath) AS jt""".as[JsonTableRow[Json]])
    assertEquals(Json.number(7), rows.head.value)
  }

  @Test def sqlNullInputYieldsNoRowsAndScalarRootYieldsOne(): Unit = {
    val empty = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                                  jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                           FROM json_tree(CAST(NULL AS JSON)) AS jt""".as[JsonTableRow[Json]])
    assertTrue(empty.isEmpty)
    val scalar = Json.number(2)
    val rows = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                                 jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                          FROM json_each($scalar) AS jt""".as[JsonTableRow[Json]])
    assertEquals(1, rows.size)
    assertEquals(Json.number(2), rows.head.value)
  }

  @Test def arrayAndMissingPaths(): Unit = {
    val document = Json("[10,20]")
    val arrayRows = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                                     jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                              FROM json_each($document) AS jt""".as[JsonTableRow[Json]])
    assertEquals(Set(Some("0"), Some("1")), arrayRows.map(_.key).toSet)
    assertEquals(Set(Json.number(10), Json.number(20)), arrayRows.map(_.value).toSet)
    val absent = JsonPath("$.missing")
    val missing = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                                   jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                            FROM json_tree($document, $absent) AS jt""".as[JsonTableRow[Json]])
    assertTrue(missing.isEmpty)
  }

  @Test def lateralTreeKeepsTraversalIdsScopedToSourceDocument(): Unit = {
    val path = JsonPath("$")
    val query = sql"""SELECT d.id, jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                            jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                     FROM (VALUES (1, CAST('{"a":1}' AS JSON)),
                                  (2, CAST('{"b":2}' AS JSON))) AS d(id, document),
                          json_tree(d.document, $path) AS jt
                     ORDER BY d.id, jt.rowid""".as[(Int, JsonTableRow[Json])](
      GetResult { r => (r.nextInt(), jsonTableRowGetResult(r)) }
    )
    assertTrue(query.statements.head.contains("json_tree(d.document, ?)"))
    val rows = run(query)
    assertEquals(4, rows.size)
    assertEquals(Seq(1, 1, 2, 2), rows.map(_._1))
    val groups = rows.groupBy(_._1).values.toSeq
    groups.foreach { group =>
      val root = group.map(_._2).find(_.parent.isEmpty).get
      assertEquals(Set(root.id), group.flatMap(_._2.parent).toSet)
      assertEquals(Set(0L, 1L), group.map(_._2.rowId).toSet)
    }
    assertEquals(groups.head.map(_._2.id).toSet, groups.last.map(_._2.id).toSet)
  }

  @Test def genericDecoderChangesOnlyJsonRepresentation(): Unit = {
    final case class AlternateJson(text: String)
    implicit val alternateResult: GetResult[JsonTableRow[AlternateJson]] =
      JsonTableRow.getResult[AlternateJson]((rs, index) => AlternateJson(rs.getString(index)))
    val document = Json("{\"n\":null}")
    val raw = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                               jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                        FROM json_each($document) AS jt""".as[JsonTableRow[Json]]).head
    val alternate = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                                     jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                              FROM json_each($document) AS jt""".as[JsonTableRow[AlternateJson]]).head
    assertEquals(AlternateJson("null"), alternate.value)
    assertEquals(None, alternate.atom)
    assertEquals(AlternateJson(document.value), alternate.inputJson)
    assertEquals(raw.id, alternate.id)
    assertEquals(raw.parent, alternate.parent)
    assertEquals(raw.rowId, alternate.rowId)
  }
}
