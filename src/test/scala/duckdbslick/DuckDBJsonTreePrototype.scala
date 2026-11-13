package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.JsonTableFunctionPrototype.jsonTree
import duckdbslick.types.{Json, JsonPath}

/** Run with `Test / runMain duckdbslick.DuckDBJsonTreePrototype`. */
object DuckDBJsonTreePrototype {
  final class Documents(tag: Tag) extends Table[(Int, Json)](tag, "documents") {
    def id = column[Int]("id", O.PrimaryKey)
    def payload = column[Json]("payload")
    def * = (id, payload)
  }

  def main(args: Array[String]): Unit = {
    val documents = TableQuery[Documents]
    val path = jsonTablePath(JsonPath("$.items"))
    val query = for {
      doc <- documents
      (fullKey, value) <- jsonTree(doc.payload, path.bind)
      if fullKey =!= "$.items"
    } yield (doc.id, fullKey, value)

    val action = query.sortBy(row => (row._1, row._2)).result
    val sqlText = action.statements.head
    println(sqlText)
    require(sqlText.contains("from \"documents\"") && sqlText.matches("(?s).*json_tree\\([^,]+\\.\"payload\", \\?\\).*"), sqlText)
    val result = DuckDBTestDatabase.run(DBIO.seq(
      documents.schema.create,
      documents ++= Seq(
        (1, Json("""{"items":[11,null],"unselected":99}""")),
        (2, Json("""{"items":[22],"unselected":88}"""))
      )
    ).andThen(action))
    result.foreach(println)
    require(result == Seq(
      (1, "$.items[0]", Json.number(11)),
      (1, "$.items[1]", Json.nullValue),
      (2, "$.items[0]", Json.number(22))
    ), result.toString)
  }
}
