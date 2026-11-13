package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.{Json, JsonPath}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

class DuckDBLiftedJsonTreeTest {
  final class Documents(tag: Tag) extends Table[(Int, Json)](tag, "documents") {
    def id = column[Int]("id", O.PrimaryKey)
    def payload = column[Json]("payload")
    def * = (id, payload)
  }

  private val documents = TableQuery[Documents]

  @Test def expandsTwoDocumentsWithABoundPath(): Unit = {
    val path = jsonTablePath(JsonPath("$.items"))
    val query = for {
      doc <- documents
      (fullKey, value) <- jsonTree(doc.payload, path.bind)
      if fullKey =!= "$.items"
    } yield (doc.id, fullKey, value)

    val action = query.sortBy(row => (row._1, row._2)).result
    val sqlText = action.statements.head
    assertTrue(sqlText, sqlText.contains("from \"documents\""))
    assertTrue(sqlText, sqlText.matches("(?s).*json_tree\\([^,]+\\.\"payload\", \\?\\).*"))
    val result = DuckDBTestDatabase.run(DBIO.seq(
      documents.schema.create,
      documents ++= Seq(
        (1, Json("""{"items":[11,null],"unselected":99}""")),
        (2, Json("""{"items":[22],"unselected":88}"""))
      )
    ).andThen(action))
    assertEquals(Seq(
      (1, "$.items[0]", Json.number(11)),
      (1, "$.items[1]", Json.nullValue),
      (2, "$.items[0]", Json.number(22))
    ), result)
  }

  @Test def canTraverseTheValueFromAnEarlierTraversal(): Unit = {
    val path = JsonPath("$").bind
    val query = for {
      document <- documents
      outer <- jsonTree(document.payload, path) if outer._1 === "$.item"
      inner <- jsonTree(outer._2, path) if inner._1 === "$.name"
    } yield (document.id, inner._2)

    val rows = DuckDBTestDatabase.run(DBIO.seq(
      documents.schema.create,
      documents += ((1, Json("""{"item":{"name":"duck"}}""")))
    ).andThen(query.result))
    assertEquals(Seq((1, Json(""""duck""""))), rows)
  }

  @Test def keepsAnArgumentUsedOnlyByTheTraversalAfterTake(): Unit = {
    val path = JsonPath("$").bind
    val query = for {
      document <- documents.sortBy(_.id).take(1)
      row <- jsonTree(document.payload, path) if row._1 =!= "$"
    } yield (document.id, row._1)

    val rows = DuckDBTestDatabase.run(DBIO.seq(
      documents.schema.create,
      documents ++= Seq((1, Json("""{"duck":1}""")), (2, Json("""{"goose":2}""")))
    ).andThen(query.result))
    assertEquals(Seq((1, "$.duck")), rows)
  }
}
