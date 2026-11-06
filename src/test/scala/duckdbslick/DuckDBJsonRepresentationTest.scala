package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.{Json, JsonPath}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Assume.assumeTrue
import org.junit.Test
import slick.SlickException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class DuckDBJsonRepresentationTest {
  private case class AlternateJson(text: String)

  private val alternate = jsonRepresentation[AlternateJson](
    value => Json(value.text),
    value => AlternateJson(value.value)
  )

  private implicit val alternateJdbcType: BaseColumnType[AlternateJson] =
    alternate.jdbcType

  @Test def testAlternateRepresentationRoundTrip(): Unit = {
    class Documents(tag: Tag)
        extends Table[(Int, Option[AlternateJson])](tag, "json_alternate_documents") {
      def id = column[Int]("id", O.PrimaryKey)
      def document = column[Option[AlternateJson]]("document")
      def * = (id, document)
    }
    val documents = TableQuery[Documents]
    val rows = Seq(
      (1, Some(AlternateJson("{}"))),
      (2, Some(AlternateJson("null"))),
      (3, None)
    )

    val stored = run(for {
      _ <- documents.schema.create
      _ <- documents ++= rows
      values <- documents.sortBy(_.id).result
    } yield values)
    assertEquals(rows, stored)
  }

  @Test def testAlternateRepresentationMutationsPreserveNulls(): Unit = {
    assumeTrue(DuckDBVersion.supportsJsonMutations(DuckDBProfile.getClass.getClassLoader))

    val source = LiteralColumn(AlternateJson("{}"))
    val setJsonNull = alternate.column(source).jsonSet(
      JsonPath("$.a").bind,
      AlternateJson("null").bind
    )
    val setSqlNull = alternate.column(source).jsonSet(
      JsonPath("$.a").bind,
      Option.empty[AlternateJson].bind
    )
    val removed = alternate.column(source).jsonRemove(JsonPath("$").bind)
    val nullDocument = alternate.optionalColumn(LiteralColumn(Option.empty[AlternateJson]))
      .jsonSet(JsonPath("$.a").bind, AlternateJson("1").bind)

    assertEquals(AlternateJson("""{"a":null}"""), run(Query(setJsonNull).result.head))
    assertEquals(None, run(Query(setSqlNull).result.head))
    assertEquals(None, run(Query(removed).result.head))
    assertEquals(None, run(Query(nullDocument).result.head))
  }

  @Test def testAlternateRepresentationPatchingResultAndNormalization(): Unit = {
    assumeTrue(DuckDBVersion.supportsJsonPatching(DuckDBProfile.getClass.getClassLoader))

    val source = LiteralColumn(AlternateJson("""{"b":2,"a":null}"""))
    val stripped: Rep[AlternateJson] = alternate.column(source).jsonStripNulls
    val normalized: Rep[String] = alternate.column(stripped).jsonNormalize
    val jsonNull: Rep[AlternateJson] =
      alternate.column(LiteralColumn(AlternateJson("null"))).jsonStripNulls
    val sqlNull: Rep[Option[AlternateJson]] =
      alternate.optionalColumn(LiteralColumn(Option.empty[AlternateJson])).jsonStripNulls
    val merged: Rep[AlternateJson] = alternate.column(LiteralColumn(AlternateJson("{}")))
      .jsonDeepMerge(AlternateJson("{\"n\":3}").bind)
    val diff: Rep[AlternateJson] = alternate.column(LiteralColumn(AlternateJson("{\"n\":1}")))
      .jsonMergePatchDiff(AlternateJson("{\"n\":2}").bind)

    assertEquals(AlternateJson("""{"b":2}"""), run(Query(stripped).result.head))
    assertEquals("""{"b":2}""", run(Query(normalized).result.head))
    assertEquals(AlternateJson("null"), run(Query(jsonNull).result.head))
    assertEquals(None, run(Query(sqlNull).result.head))
    assertEquals(AlternateJson("{\"n\":3}"), run(Query(merged).result.head))
    assertEquals(AlternateJson("{\"n\":2}"), run(Query(diff).result.head))
  }

  @Test def testAlternateRepresentationFailsEarlyOnOlderDrivers(): Unit = {
    assumeTrue(!DuckDBVersion.supportsJsonMutations(DuckDBProfile.getClass.getClassLoader))

    val source = LiteralColumn(AlternateJson("{}"))
    val path = JsonPath("$.a").bind
    val failure = Try(alternate.column(source).jsonSet(path, AlternateJson("1").bind)).failed.get
    assertTrue(failure.isInstanceOf[SlickException])
    assertTrue(failure.getMessage.contains("requires DuckDB JDBC 2.0 or newer"))

    val removeFailure = Try(alternate.column(source).jsonRemove(path)).failed.get
    assertTrue(removeFailure.isInstanceOf[SlickException])
    assertTrue(removeFailure.getMessage.contains("requires DuckDB JDBC 2.0 or newer"))

    val normalizeFailure = Try(alternate.column(source).jsonNormalize).failed.get
    assertTrue(normalizeFailure.isInstanceOf[SlickException])
    assertTrue(normalizeFailure.getMessage.contains("JSON patching and normalization"))

    val stripFailure = Try(alternate.column(source).jsonStripNulls).failed.get
    assertTrue(stripFailure.isInstanceOf[SlickException])
    assertTrue(stripFailure.getMessage.contains("JSON patching and normalization"))

    val mergeFailure = Try(alternate.column(source)
      .jsonDeepMerge(AlternateJson("{}").bind)).failed.get
    assertTrue(mergeFailure.isInstanceOf[SlickException])
    val diffFailure = Try(alternate.column(source)
      .jsonMergePatchDiff(AlternateJson("{}").bind)).failed.get
    assertTrue(diffFailure.isInstanceOf[SlickException])
  }
}
