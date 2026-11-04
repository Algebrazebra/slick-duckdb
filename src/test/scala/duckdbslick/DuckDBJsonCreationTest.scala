package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.Json
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

class DuckDBJsonCreationTest {
  @Test def scalarConstructorsKeepRawAndEncodedValuesDistinct(): Unit = {
    assertEquals(Json("2"), Json.number(2))
    assertEquals(Json("9223372036854775807"), Json.number(Long.MaxValue))
    assertEquals(Json("123456789012345678901234567890"), Json.number(BigInt("123456789012345678901234567890")))
    assertEquals(Json("2.5"), Json.number(BigDecimal("2.5")))
    assertEquals(Json("true"), Json.boolean(true))
    assertEquals(Json("null"), Json.nullValue)
    assertEquals(Json("duck"), Json("duck"))
    assertEquals(Json("\"duck\""), Json.string("duck"))
    assertEquals(Json("\"\\\"\""), Json.string("\""))
    assertEquals(Json("\"\\\\\""), Json.string("\\"))
    assertEquals(Json("\"\\n\""), Json.string("\n"))
    assertEquals(Json("\"\\u0001\""), Json.string("\u0001"))
    assertEquals(Json("\"é😀\""), Json.string("é😀"))
  }

  @Test def stringRejectsInvalidUnicode(): Unit = {
    assertTrue(scala.util.Try(Json.string("\ud800")).isFailure)
    assertTrue(scala.util.Try(Json.string("\udc00")).isFailure)
    assertTrue(scala.util.Try(Json.string(null)).failed.get.isInstanceOf[IllegalArgumentException])
  }

  @Test def toJsonPreservesSqlTypeAndNull(): Unit = {
    assertEquals(Json("\"duck\""), run(Query(toJson("duck".bind)).result.head))
    assertEquals(Json("12"), run(Query(toJson(12.bind)).result.head))
    assertEquals(Json("\"duck\""), run(Query(toJson(Json.string("duck").bind)).result.head))
    assertEquals(Json("\"literal\""), run(Query(toJson(LiteralColumn("literal"))).result.head))
    assertEquals(None, run(Query(toJsonOption(Option.empty[String].bind)).result.head))
    assertEquals(Some(Json("\"duck\"")),
      run(Query(toJsonOption(Option("duck").bind)).result.head))
  }

  @Test def heterogeneousArrayAndObjectPreserveNullElements(): Unit = {
    val array = Query(jsonArray("duck".bind, 2.bind, Json.nullValue.bind,
      Option.empty[String].bind, true.bind))
    val obj = Query(jsonObject(
      "name".bind -> "duck".bind,
      "count".bind -> 2.bind,
      "missing".bind -> Option.empty[Int].bind,
      "explicit".bind -> Json.nullValue.bind
    ))

    assertEquals(Json("[]"), run(Query(jsonArray()).result.head))
    assertEquals(Json("{}"), run(Query(jsonObject()).result.head))
    assertEquals(Json("{\"one\":1}"),
      run(Query(jsonObject("one".bind -> 1.bind)).result.head))
    assertEquals(Json("[null]"), run(Query(jsonArray(Option.empty[Int].bind)).result.head))
    assertEquals(Json("[null]"), run(Query(jsonArray(Json.nullValue.bind)).result.head))
    assertEquals(Json("[\"duck\",2,null,null,true]"), run(array.result.head))
    assertEquals(Json("{\"name\":\"duck\",\"count\":2,\"missing\":null,\"explicit\":null}"),
      run(obj.result.head))
    assertEquals(5, array.result.statements.head.count(_ == '?'))
    assertEquals(8, obj.result.statements.head.count(_ == '?'))
  }

  @Test def mergePatchHasAsymmetricSqlNulls(): Unit = {
    val doc = Json("{\"a\":1}").bind
    val patch = Json("{\"a\":null}").bind
    assertEquals(Json("{}"), run(Query(jsonMergePatch(doc, patch)).result.head))
    // DuckDB uses the patch when the document is SQL NULL.
    assertEquals(Json("{\"b\":2}"), run(Query(
      jsonMergePatchNullableDocument(Option.empty[Json].bind, Json("{\"b\":2}").bind)
    ).result.head))
    // A SQL NULL patch instead makes the result SQL NULL.
    assertEquals(None, run(Query(
      jsonMergePatchNullablePatch(doc, Option.empty[Json].bind)
    ).result.head))
    assertEquals(None, run(Query(
      jsonMergePatchNullable(Option.empty[Json].bind, Option.empty[Json].bind)
    ).result.head))
  }

}
