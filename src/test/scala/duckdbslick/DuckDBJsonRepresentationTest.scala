package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.Json
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.concurrent.ExecutionContext.Implicits.global

private final case class AlternateJson(text: String)
private final case class JsonFlag(value: Boolean)

class DuckDBJsonRepresentationTest {

  private implicit val alternateRepresentation: JsonRepresentation[AlternateJson] =
    jsonRepresentation[AlternateJson](_.text, AlternateJson(_))

  private implicit val alternateJdbcType: BaseColumnType[AlternateJson] =
    alternateRepresentation.jdbcType

  @Test def alternateMappingPreservesJsonNullAndSqlNullInColumns(): Unit = {
    class Documents(tag: Tag) extends Table[(Int, Option[AlternateJson])](tag, "alternate_json") {
      def id = column[Int]("id", O.PrimaryKey)
      def document = column[Option[AlternateJson]]("document")
      def * = (id, document)
    }
    val documents = TableQuery[Documents]
    val rows = Seq((1, Some(AlternateJson("null"))), (2, None))
    val stored = run(for {
      _ <- documents.schema.create
      _ <- documents ++= rows
      found <- documents.sortBy(_.id).result
    } yield found)

    assertTrue(documents.schema.createStatements.mkString(" ").contains("JSON"))
    assertEquals(rows, stored)
  }

  @Test def alternateCreationResultsUseSelectedMapping(): Unit = {
    assertEquals(AlternateJson("\"duck\""),
      run(Query(toJsonAs[AlternateJson, String]("duck".bind)).result.head))
    assertEquals(None, run(Query(
      toJsonAs[AlternateJson, String](Option.empty[String].bind)
    ).result.head))
    assertEquals(AlternateJson("[1,null]"), run(Query(
      jsonArrayAs[AlternateJson](1.bind, Option.empty[String].bind)
    ).result.head))
    assertEquals(AlternateJson("[null]"), run(Query(
      jsonArrayAs[AlternateJson](AlternateJson("null").bind)
    ).result.head))
    assertEquals(AlternateJson("{\"a\":null}"), run(Query(
      jsonObjectAs[AlternateJson]("a".bind -> AlternateJson("null").bind)
    ).result.head))
  }

  @Test def alternateMergePatchRetainsRepresentationAndNullSemantics(): Unit = {
    val document = AlternateJson("{\"a\":1}").bind
    assertEquals(AlternateJson("{}"), run(Query(
      jsonMergePatchAs(document, AlternateJson("{\"a\":null}").bind)
    ).result.head))
    assertEquals(AlternateJson("{}"), run(Query(
      jsonMergePatchAs(Option.empty[AlternateJson].bind,
        AlternateJson("{}").bind)
    ).result.head))
    assertEquals(None, run(Query(
      jsonMergePatchAs(document, Option.empty[AlternateJson].bind)
    ).result.head))
    assertEquals(None, run(Query(
      jsonMergePatchAs(Option.empty[AlternateJson].bind,
        Option.empty[AlternateJson].bind)
    ).result.head))
  }

  @Test def rawAndAlternateApisCoexistWithoutAmbiguity(): Unit = {
    implicit val flagRepresentation: JsonRepresentation[JsonFlag] =
      jsonRepresentation[JsonFlag](_.value.toString, text => JsonFlag(text.toBoolean))
    implicit val flagJdbcType: BaseColumnType[JsonFlag] = flagRepresentation.jdbcType

    val alternate = AlternateJson("{}").bind
    val flag = JsonFlag(true).bind
    assertEquals(AlternateJson("{}"), run(Query(jsonMergePatchAs(alternate, alternate)).result.head))
    assertEquals(JsonFlag(true), run(Query(jsonMergePatchAs(flag, flag)).result.head))
    assertEquals(Json("[null]"), run(Query(jsonArray(Json.nullValue.bind)).result.head))
    assertEquals(Json("{}"), run(Query(
      jsonMergePatch(Json("{\"a\":1}").bind, Json("{\"a\":null}").bind)
    ).result.head))
  }
}
