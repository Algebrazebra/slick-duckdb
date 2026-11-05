package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.{Json, JsonScalarPath}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import scala.util.Try

class DuckDBJsonExtractionTest {
  @Test def extractPresentAndMissingValues(): Unit = {
    val document = LiteralColumn(Json("""{"species":["duck"]}"""))
    val present: Rep[Option[Json]] = document.jsonExtract(JsonScalarPath("$.species[0]").bind)
    val missing: Rep[Option[Json]] = document.jsonExtract(JsonScalarPath("$.missing").bind)
    val sql = Query(present).result.statements.head.toLowerCase

    assertTrue(sql, sql.contains("json_extract("))
    assertEquals(2, sql.count(_ == '?'))
    assertEquals(Some(Json(""""duck"""")), run(Query(present).result.head))
    assertEquals(None, run(Query(missing).result.head))
  }

  @Test def extractTextAndJsonValueHaveDistinctResults(): Unit = {
    val document = LiteralColumn(Json("""{"species":["duck"],"meta":{"ok":true},"empty":null}"""))
    val text: Rep[Option[String]] = document.jsonExtractText(JsonScalarPath("$.species[0]").bind)
    val objectValue: Rep[Option[Json]] = document.jsonValue(JsonScalarPath("$.meta").bind)
    val scalarValue: Rep[Option[Json]] = document.jsonValue(JsonScalarPath("$.species[0]").bind)
    val jsonNull: Rep[Option[Json]] = document.jsonExtract(JsonScalarPath("$.empty").bind)
    val valueSql = Query(scalarValue).result.statements.head.toLowerCase
    val textSql = Query(text).result.statements.head.toLowerCase

    assertTrue(textSql, textSql.contains("json_extract_string("))
    assertEquals(2, textSql.count(_ == '?'))
    assertTrue(valueSql, valueSql.contains("cast(json_value("))
    assertTrue(valueSql, valueSql.contains(" as json)"))
    assertEquals(Some("duck"), run(Query(text).result.head))
    assertEquals(None, run(Query(objectValue).result.head))
    assertEquals(Some(Json(""""duck"""")), run(Query(scalarValue).result.head))
    assertEquals(Some(Json("null")), run(Query(jsonNull).result.head))
  }

  @Test def existsAndNullableInputs(): Unit = {
    val document = LiteralColumn(Json("""{"a":null}"""))
    val present: Rep[Boolean] = document.jsonExists(JsonScalarPath("$.a").bind)
    val missing: Rep[Boolean] = document.jsonExists(JsonScalarPath("$.missing").bind)
    val nullDocument: Rep[Option[Boolean]] =
      LiteralColumn(Option.empty[Json]).jsonExists(JsonScalarPath("$.a").bind)
    val nullPath: Rep[Option[Json]] =
      document.jsonExtract(Option.empty[JsonScalarPath].bind)
    val nullExistsPath: Rep[Option[Boolean]] =
      document.jsonExists(Option.empty[JsonScalarPath].bind)
    val sql = Query(present).result.statements.head.toLowerCase

    assertTrue(sql, sql.contains("json_exists("))
    assertEquals(2, sql.count(_ == '?'))
    assertEquals(true, run(Query(present).result.head))
    assertEquals(false, run(Query(missing).result.head))
    assertEquals(None, run(Query(nullDocument).result.head))
    assertEquals(None, run(Query(nullPath).result.head))
    assertEquals(None, run(Query(nullExistsPath).result.head))
  }

  @Test def scalarPathRejectsMultiResultSyntaxButAcceptsEscapedKeys(): Unit = {
    Seq("$.a[*]", "$.a.*", "$.a[0,1]", "$.a[?(@.x)]", "/a~2b")
      .foreach(path => assertTrue(Try(JsonScalarPath(path)).isFailure))

    val doc = LiteralColumn(Json("""{"a.*":7,"a/b":8}"""))
    assertEquals(Some(Json("7")), run(Query(doc.jsonExtract(JsonScalarPath("$.\"a.*\"").bind)).result.head))
    assertEquals(Some(Json("8")), run(Query(doc.jsonExtract(JsonScalarPath("/a~1b").bind)).result.head))
    assertEquals(Some(Json("""{"a.*":7,"a/b":8}""")), run(Query(doc.jsonExtract(JsonScalarPath("").bind)).result.head))
    val last = LiteralColumn(Json("""{"a":[1,2]}"""))
    assertEquals(Some(Json("2")), run(Query(last.jsonExtract(JsonScalarPath("$.a[#-1]").bind)).result.head))
  }

  @Test def databasePathColumnsAreNotTreatedAsValidatedLiterals(): Unit = {
    class Documents(tag: Tag) extends Table[(Json, JsonScalarPath)](tag, "extraction_path_columns") {
      def document = column[Json]("document")
      def path = column[JsonScalarPath]("path")
      def * = (document, path)
    }
    val documents = TableQuery[Documents]

    assertTrue(Try(documents.map(row => row.document.jsonExtract(row.path))).isFailure)
  }

  @Test def validatedLiteralPathCanBeUsedWithoutBinding(): Unit = {
    val document = LiteralColumn(Json("""{"a":1}"""))
    val path = LiteralColumn(JsonScalarPath("$.a"))
    assertEquals(Some(Json("1")), run(Query(document.jsonExtract(path)).result.head))
  }

  @Test def operatorsRenderAndChainWithBoundOperands(): Unit = {
    val document = Json("""{"species":{"name":"duck"}}""").bind
    val species = JsonScalarPath("$.species").bind
    val name = JsonScalarPath("$.name").bind
    val extracted: Rep[Option[Json]] = document -> species
    val text: Rep[Option[String]] = (document -> species) ->> name
    val query = Query((extracted, text))
    val sql = query.result.statements.head.toLowerCase

    assertTrue(sql.contains("->"))
    assertTrue(sql.contains("->>"))
    assertTrue(sql, sql.contains("(? -> ?)"))
    assertTrue(sql, sql.contains("((? -> ?) ->> ?)"))
    assertEquals(5, sql.count(_ == '?'))
    assertEquals((Some(Json("""{"name":"duck"}""")), Some("duck")), run(query.result.head))
  }

  @Test def operatorsRemainGroupedInsideEqualityAndBooleanPredicates(): Unit = {
    val document = Json("""{"name":"duck","active":true}""").bind
    val name = JsonScalarPath("$.name").bind
    val active = JsonScalarPath("$.active").bind
    val source = Query(document)
    val andQuery = source.filter { doc =>
      ((doc ->> name) === Option("duck")) && ((doc -> active) === Option(Json("true")))
    }
    val orQuery = source.filter { doc =>
      ((doc ->> name) === Option("duck")) || ((doc -> active) === Option(Json("true")))
    }

    Seq(andQuery, orQuery).foreach { query =>
      val sql = query.result.statements.head.toLowerCase
      assertTrue(sql, sql.contains(" where "))
      assertTrue(sql, sql.contains("(? ->> ?)"))
      assertTrue(sql, sql.contains("(? -> ?)"))
      assertEquals(Seq(Json("""{"name":"duck","active":true}""")), run(query.result))
    }
  }

  @Test def sqlNullAndJsonNullStayDistinctThroughOperators(): Unit = {
    val root = JsonScalarPath("$").bind
    val jsonNull = Json("null").bind
    val sqlNull = Option.empty[Json].bind

    val jsonResult: Rep[Option[Json]] = jsonNull -> root
    val textResult: Rep[Option[String]] = jsonNull ->> root
    val sqlResult: Rep[Option[Json]] = sqlNull -> root
    val nullPathResult: Rep[Option[String]] =
      jsonNull ->> Option.empty[JsonScalarPath].bind

    assertEquals(Some(Json("null")), run(Query(jsonResult).result.head))
    assertEquals(None, run(Query(textResult).result.head))
    assertEquals(None, run(Query(sqlResult).result.head))
    assertEquals(None, run(Query(nullPathResult).result.head))
    assertEquals(None, run(Query(jsonNull.jsonValue(root)).result.head))
  }
}
