package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.Json
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.concurrent.ExecutionContext.Implicits.global

class DuckDBJsonAggregateTest {
  private case class AlternateJson(value: String)
  private implicit val alternateJsonJdbcType: BaseColumnType[AlternateJson] =
    MappedColumnType.base[AlternateJson, Json](value => Json(value.value), json => AlternateJson(json.value))

  class Rows(tag: Tag) extends Table[(Int, String, String, Option[Int], Option[Json])](tag, "json_aggregate_rows") {
    def id = column[Int]("id")
    def category = column[String]("category")
    def key = column[String]("key")
    def amount = column[Option[Int]]("amount")
    def document = column[Option[Json]]("document")
    def * = (id, category, key, amount, document)
  }
  val rows = TableQuery[Rows]

  private val data = Seq(
    (1, "a", "x", Some(1), Some(Json("""{"a":1}"""))),
    (2, "a", "y", Some(2), Some(Json("""{"b":true}"""))),
    (3, "b", "z", None, Some(Json("null")))
  )

  @Test def groupedAndUngroupedAggregateShapes(): Unit = {
    val array: Rep[Option[Json]] = rows.map(_.amount).jsonGroupArray
    val obj: Rep[Option[Json]] = rows.map(r => (r.key, r.amount)).jsonGroupObject
    val structure: Rep[Option[Json]] = rows.map(_.document).jsonGroupStructure
    val grouped = rows.groupBy(_.category).map { case (category, group) =>
      (category, group.map(_.amount).jsonGroupArray)
    }
    val groupedObjects = rows.groupBy(_.category).map { case (category, group) =>
      (category, group.map(r => (r.key, r.amount)).jsonGroupObject)
    }
    val groupedStructures = rows.groupBy(_.category).map { case (category, group) =>
      (category, group.map(_.document).jsonGroupStructure)
    }
    val sql = Query((array, obj, structure)).result.statements.head.toLowerCase
    assertTrue(sql, sql.contains("json_group_array("))
    assertTrue(sql, sql.contains("json_group_object("))
    assertTrue(sql, sql.contains("json_group_structure("))
    assertTrue(grouped.result.statements.head.toLowerCase.contains("group by"))
    assertTrue(groupedObjects.result.statements.head.toLowerCase.contains("json_group_object("))
    assertTrue(groupedStructures.result.statements.head.toLowerCase.contains("json_group_structure("))

    val action = DBIO.seq(rows.schema.create, rows ++= data).andThen(for {
      a <- Query(array).result.head
      o <- Query(obj).result.head
      s <- Query(structure).result.head
      g <- grouped.result
      go <- groupedObjects.result
      gs <- groupedStructures.result
    } yield (a, o, s, g, go, gs))
    val (arrayResult, objectResult, structureResult, groupedResult,
      groupedObjectResult, groupedStructureResult) = run(action)
    val arrayParts = arrayResult.get.value.stripPrefix("[").stripSuffix("]").split(",").toSeq
    assertEquals(Seq("1", "2", "null"), arrayParts.sorted)
    val objectParts = objectResult.get.value.stripPrefix("{").stripSuffix("}").split(",").toSeq
    assertEquals(Seq("\"x\":1", "\"y\":2", "\"z\":null"), objectParts.sorted)
    val structureParts = structureResult.get.value.stripPrefix("{").stripSuffix("}").split(",").toSeq
    assertEquals(Seq("\"a\":\"UBIGINT\"", "\"b\":\"BOOLEAN\""), structureParts.sorted)
    val groups = groupedResult.toMap
    assertTrue(Set(Json("[1,2]"), Json("[2,1]")).contains(groups("a").get))
    assertEquals(Some(Json("[null]")), groups("b"))
    assertEquals(None, groups.get("missing"))

    val groupedObjectResults = groupedObjectResult.toMap
    assertTrue(Set(Json("""{"x":1,"y":2}"""), Json("""{"y":2,"x":1}"""))
      .contains(groupedObjectResults("a").get))
    assertEquals(Some(Json("""{"z":null}""")), groupedObjectResults("b"))
    val groupedStructureResults = groupedStructureResult.toMap
    assertEquals(Some(Json(""""NULL"""")), groupedStructureResults("b"))
  }

  @Test def emptyInputsAndBoundValues(): Unit = {
    val empty = rows.filter(_.id === 999)
    val numeric: Rep[Option[Json]] = empty.map(_.id).jsonGroupArray
    val obj: Rep[Option[Json]] = empty.map(r => (r.key, r.amount)).jsonGroupObject
    val structure: Rep[Option[Json]] = empty.map(_.document).jsonGroupStructure
    val boundArray = rows.filter(_.id === 1).map(_ => "duck".bind).jsonGroupArray
    val boundObject = rows.filter(_.id === 1).map(r => (r.key, true.bind)).jsonGroupObject
    val sql = Query((boundArray, boundObject)).result.statements.head.toLowerCase
    assertTrue(sql, sql.contains("json_group_array("))
    assertTrue(sql, sql.contains("json_group_object("))
    assertEquals(2, sql.count(_ == '?'))

    val action = DBIO.seq(rows.schema.create, rows ++= data).andThen(
      for {
        a <- Query(numeric).result.head
        o <- Query(obj).result.head
        s <- Query(structure).result.head
        ba <- Query(boundArray).result.head
        bo <- Query(boundObject).result.head
      } yield (a, o, s, ba, bo)
    )
    assertEquals((None, None, None, Some(Json("[\"duck\"]")),
      Some(Json("""{"x":true}"""))), run(action))
  }

  @Test def sqlNullJsonNullAndDuplicateKeys(): Unit = {
    val duplicates = rows.filter(_.category === "a").map(r => ("same".bind, r.amount)).jsonGroupObject
    val jsonArray = rows.filter(_.category === "b").map(_.document).jsonGroupArray
    val sqlArray = rows.filter(_.category === "b").map(_.amount).jsonGroupArray
    val allNullStructure = rows.filter(_.category === "b").map(_.document).jsonGroupStructure
    val action = DBIO.seq(rows.schema.create, rows ++= data).andThen(
      for {
        d <- Query(duplicates).result.head
        j <- Query(jsonArray).result.head
        s <- Query(sqlArray).result.head
        st <- Query(allNullStructure).result.head
      } yield (d, j, s, st)
    )
    val (duplicate, json, sql, structure) = run(action)
    assertTrue(Set(Json("""{"same":1,"same":2}"""), Json("""{"same":2,"same":1}"""))
      .contains(duplicate.get))
    assertEquals(Some(Json("[null]")), json)
    assertEquals(Some(Json("[null]")), sql)
    assertEquals(Some(Json(""""NULL"""")), structure)
  }

  @Test def tupleQueryAndSqlNullDocument(): Unit = {
    val one = Query(("name".bind, Json.string("duck").bind)).jsonGroupObject
    val sql = Query(one).result.statements.head.toLowerCase
    assertTrue(sql, sql.contains("json_group_object("))
    assertEquals(Some(Json("""{"name":"duck"}""")), run(Query(one).result.head))

    val boundJsonArray = Query(Json.nullValue.bind).jsonGroupArray
    val boundJsonStructure = Query(Json.nullValue.bind).jsonGroupStructure
    assertEquals(Some(Json("[null]")), run(Query(boundJsonArray).result.head))
    assertEquals(Some(Json(""""NULL"""")), run(Query(boundJsonStructure).result.head))

    val nullDocument = rows.filter(_.id === 4).map(_.document).jsonGroupStructure
    val action = DBIO.seq(rows.schema.create,
      rows += ((4, "c", "null", None, None))).andThen(Query(nullDocument).result.head)
    assertEquals(Some(Json(""""NULL"""")), run(action))
  }

  @Test def explicitlySelectedJsonResultMapping(): Unit = {
    val presentArray: Rep[Option[AlternateJson]] =
      Query(2.bind).jsonGroupArrayAs(alternateJsonJdbcType)
    val presentObject: Rep[Option[AlternateJson]] =
      Query(("a".bind, 2.bind)).jsonGroupObjectAs(alternateJsonJdbcType)
    val presentStructure: Rep[Option[AlternateJson]] =
      Query(AlternateJson("""{"a":1}""").bind).jsonGroupStructureAs(alternateJsonJdbcType)
    val empty: Rep[Option[AlternateJson]] =
      rows.filter(_.id === 999).map(_.amount).jsonGroupArrayAs(alternateJsonJdbcType)

    assertEquals(Some(AlternateJson("[2]")), run(Query(presentArray).result.head))
    assertEquals(Some(AlternateJson("""{"a":2}""")), run(Query(presentObject).result.head))
    assertEquals(Some(AlternateJson("""{"a":"UBIGINT"}""")),
      run(Query(presentStructure).result.head))
    assertEquals(None, run(DBIO.seq(rows.schema.create).andThen(Query(empty).result.head)))
  }
}
