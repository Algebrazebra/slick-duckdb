package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.{Json, JsonPath}
import org.junit.Assume.assumeTrue
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import slick.SlickException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class DuckDBJsonMutationTest {
  private val supportsMutations =
    DuckDBVersion.supportsJsonMutations(DuckDBProfile.getClass.getClassLoader)

  @Test def testSetBindsJsonValueAndNestedPath(): Unit = {
    assumeTrue(supportsMutations)

    val query = Query(
      LiteralColumn(Json("""{"a":{}}""")).jsonSet(
        JsonPath("$.a.b").bind,
        Json(""""duck"""").bind
      )
    )

    val sql = query.result.statements.head.toLowerCase
    assertTrue(sql.contains("json_set("))
    assertEquals(3, sql.count(_ == '?'))
    assertEquals(Json("""{"a":{"b":"duck"}}"""), run(query.result.head))
  }

  @Test def testInsertOnlyFillsMissingPath(): Unit = {
    assumeTrue(supportsMutations)

    val source = LiteralColumn(Json("""{"a":1}"""))
    val existing = Query(source.jsonInsert(JsonPath("$.a").bind, Json("9").bind))
    val missing = Query(source.jsonInsert(JsonPath("$.b").bind, Json("2").bind))

    assertTrue(existing.result.statements.head.toLowerCase.contains("json_insert("))
    assertEquals(Json("""{"a":1}"""), run(existing.result.head))
    assertEquals(Json("""{"a":1,"b":2}"""), run(missing.result.head))
  }

  @Test def testReplaceOnlyUpdatesExistingPath(): Unit = {
    assumeTrue(supportsMutations)

    val source = LiteralColumn(Json("""{"a":[0,1]}"""))
    val existing = Query(source.jsonReplace(JsonPath("$.a[1]").bind, Json("7").bind))
    val missing = Query(source.jsonReplace(JsonPath("$.b").bind, Json("2").bind))

    assertTrue(existing.result.statements.head.toLowerCase.contains("json_replace("))
    assertEquals(Json("""{"a":[0,7]}"""), run(existing.result.head))
    assertEquals(Json("""{"a":[0,1]}"""), run(missing.result.head))
  }

  @Test def testRemoveAcceptsJsonPointer(): Unit = {
    assumeTrue(supportsMutations)

    val query = Query(
      LiteralColumn(Json("""{"a":{"b":1},"c":2}""")).jsonRemove(
        JsonPath("/a/b").bind
      )
    )

    val sql = query.result.statements.head.toLowerCase
    assertTrue(sql.contains("json_remove("))
    assertEquals(2, sql.count(_ == '?'))
    assertEquals(Some(Json("""{"a":{},"c":2}""")), run(query.result.head))
  }

  @Test def testRemovingRootReturnsSqlNull(): Unit = {
    assumeTrue(supportsMutations)

    val removed: Rep[Option[Json]] = LiteralColumn(Json("""{"a":1}""")).jsonRemove(
      JsonPath("$").bind
    )

    assertEquals(None, run(Query(removed).result.head))
  }

  @Test def testSqlNullAndJsonNullRemainDistinct(): Unit = {
    assumeTrue(supportsMutations)

    val source = LiteralColumn(Json("{}"))
    val sqlNullValue = Query(
      source.jsonSet(JsonPath("$.a").bind, Option.empty[Json].bind)
    )
    val jsonNullValue = Query(
      source.jsonSet(JsonPath("$.a").bind, Json("null").bind)
    )
    val sqlNullDocument = Query(
      LiteralColumn(Option.empty[Json]).jsonSet(
        JsonPath("$.a").bind,
        Json("1").bind
      )
    )
    val sqlNullPath = Query(
      source.jsonSet(Option.empty[JsonPath].bind, Json("1").bind)
    )

    assertEquals(None, run(sqlNullValue.result.head))
    assertEquals(Json("""{"a":null}"""), run(jsonNullValue.result.head))
    assertEquals(None, run(sqlNullDocument.result.head))
    assertEquals(None, run(sqlNullPath.result.head))
  }

  @Test def testMutationOnNullableJsonColumn(): Unit = {
    assumeTrue(supportsMutations)

    class Documents(tag: Tag)
        extends Table[(Int, Option[Json])](tag, "json_mutation_column") {
      def id = column[Int]("id", O.PrimaryKey)
      def document = column[Option[Json]]("document")
      def * = (id, document)
    }
    val documents = TableQuery[Documents]

    val results = run(for {
      _ <- documents.schema.create
      _ <- documents ++= Seq(
        (1, Some(Json("""{"a":1}"""))),
        (2, None)
      )
      results <- documents.sortBy(_.id).map(
        _.document.jsonSet(JsonPath("$.b").bind, Json("2").bind)
      ).result
    } yield results)

    assertEquals(Seq(Some(Json("""{"a":1,"b":2}""")), None), results)
  }

  @Test def testJsonColumnRoundTripOnEverySupportedDriver(): Unit = {
    class Documents(tag: Tag)
        extends Table[(Int, Option[Json])](tag, "json_documents") {
      def id = column[Int]("id", O.PrimaryKey)
      def document = column[Option[Json]]("document")
      def * = (id, document)
    }
    val documents = TableQuery[Documents]
    val rows = Seq(
      (1, Some(Json("""{"a":1}"""))),
      (2, Some(Json("null"))),
      (3, None)
    )

    val (ddl, stored) = run(for {
      _ <- documents.schema.create
      _ <- documents ++= rows
      stored <- documents.sortBy(_.id).result
    } yield (documents.schema.createStatements.mkString(" "), stored))

    assertTrue(ddl.contains("\"document\" JSON"))
    assertEquals(rows, stored)
  }

  @Test def testExistingPlainSqlJsonQueryOnEverySupportedDriver(): Unit = {
    val document = """{"a":1}"""
    val extracted = run(sql"select json_extract($document, '$$.a')".as[String].head)

    assertEquals("1", extracted)
  }

  @Test def testMutationMethodsRejectOlderDriversBeforeExecution(): Unit = {
    assumeTrue(!supportsMutations)

    val source = LiteralColumn(Json("{}"))
    val path = JsonPath("$.a").bind
    val value = Json("1").bind
    val attempts = Seq(
      () => source.jsonSet(path, value),
      () => source.jsonInsert(path, value),
      () => source.jsonReplace(path, value),
      () => source.jsonRemove(path)
    )

    attempts.foreach { attempt =>
      val failure = Try(attempt()).failed.get
      assertTrue(failure.isInstanceOf[SlickException])
      assertTrue(failure.getMessage.contains("requires DuckDB JDBC 2.0 or newer"))
    }
  }
}
