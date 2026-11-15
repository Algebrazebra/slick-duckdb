package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.Json
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

class DuckDBJsonExpansionTest {
  private class Documents(tag: Tag) extends Table[(Int, Option[Json])](tag, "documents") {
    def id = column[Int]("id", O.PrimaryKey)
    def payload = column[Option[Json]]("payload")
    def * = (id, payload)
  }

  private val documents = TableQuery[Documents]

  @Test def arrayExpansionPreservesSourceRowsAndJsonNull(): Unit = {
    val query = documents.map(d => (d.id, d.payload.jsonArrayElements))
    val action = query.result
    val sql = action.statements.head
    assertTrue(sql, sql.contains("unnest(json_extract("))
    val rows = run(DBIO.seq(
      documents.schema.create,
      documents ++= Seq(
        (1, Some(Json("[1,null,1]"))),
        (2, Some(Json("""[{"name":"goose"}]"""))),
        (3, Some(Json("[]"))),
        (4, None),
        (5, Some(Json("{}"))),
        (6, Some(Json.nullValue))
      )
    ).andThen(action))
    assertEquals(
      Seq((1, "1"), (1, "1"), (1, "null"), (2, """{"name":"goose"}""")),
      rows.map { case (id, element) => (id, element.value) }.sorted
    )
  }

  @Test def textExpansionBindsTheDocumentAndDecodesNullableElements(): Unit = {
    val array = Json("""["duck",null,2]""").bind
    val text: Rep[Option[String]] = array.jsonArrayElementsText
    val action = Query(text).result
    val sql = action.statements.head
    assertTrue(sql, sql.contains("unnest(json_extract_string(?, '$[*]'))"))
    assertEquals(Seq(None, Some("2"), Some("duck")), run(action).sorted)
  }

  @Test def keyExpansionReturnsOneRowPerObjectKey(): Unit = {
    val action = documents.map(d => (d.id, d.payload.jsonObjectKeys)).result
    val sql = action.statements.head
    assertTrue(sql, sql.contains("unnest(json_keys("))
    val rows = run(DBIO.seq(
      documents.schema.create,
      documents ++= Seq(
        (1, Some(Json("""{"duck":null,"goose":2}"""))),
        (2, Some(Json("""{"duck":3}"""))),
        (3, Some(Json("{}"))),
        (4, None),
        (5, Some(Json("[1]")))
      )
    ).andThen(action))
    assertEquals(Seq((1, "duck"), (1, "goose"), (2, "duck")), rows.sorted)
  }

  @Test def arrayExpansionReadsTheSelectedJsonRepresentation(): Unit = {
    case class AppJson(text: String)
    object ArrayProfile extends DuckDBJsonSupport[AppJson] {
      protected val jsonCodec = duckdbslick.types.JsonCodec[AppJson](_.text, AppJson(_))
    }
    import ArrayProfile.api.*
    val document = AppJson("[2,null]").bind
    val element: Rep[AppJson] = document.jsonArrayElements
    assertEquals(Seq(AppJson("2"), AppJson("null")), run(Query(element).result).sortBy(_.text))
  }
}
