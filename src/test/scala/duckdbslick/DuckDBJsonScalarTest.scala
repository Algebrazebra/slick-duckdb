package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.{Json, JsonScalarPath}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import java.sql.DriverManager
import scala.util.Using

class DuckDBJsonScalarTest {
  @Test def scalarFunctionsExposeTheirSqlTypes(): Unit = {
    val document = Json("""{"a":[1,2],"name":"duck"}""").bind
    val length: Rep[BigInt] = document.jsonArrayLength
    val contains: Rep[Boolean] = document.jsonContains(Json("""{"a":[1]}""").bind)
    val keys: Rep[Vector[String]] = document.jsonKeys
    val pretty: Rep[String] = document.jsonPretty
    val structure: Rep[Json] = document.jsonStructure
    val kind: Rep[String] = document.jsonType
    val valid: Rep[Boolean] = jsonValid("{}".bind)
    val parsed: Rep[Json] = jsonParse(" { \"a\" : 1 } ".bind)

    assertEquals(BigInt(0), run(Query(length).result.head))
    assertEquals(true, run(Query(contains).result.head))
    assertEquals(Vector("a", "name"), run(Query(keys).result.head))
    assertEquals(true, run(Query(pretty).result.head).contains("\n"))
    assertEquals(Json("""{"a":["UBIGINT"],"name":"VARCHAR"}"""), run(Query(structure).result.head))
    assertEquals("OBJECT", run(Query(kind).result.head))
    assertEquals(true, run(Query(valid).result.head))
    assertEquals(Json("""{"a":1}"""), run(Query(parsed).result.head))
    assertEquals(1, Query(parsed).result.statements.head.count(_ == '?'))
  }

  @Test def lengthsAndKeysDistinguishArraysAndObjects(): Unit = {
    assertEquals(BigInt(3), run(Query(Json("[1,2,3]").bind.jsonArrayLength).result.head))
    assertEquals(true, run(Query(Json("[1,2]").bind.jsonArrayLength === BigInt(2).bind).result.head))
    assertEquals(BigInt(0), run(Query(Json("{}").bind.jsonArrayLength).result.head))
    assertEquals(BigInt(0), run(Query(Json("null").bind.jsonArrayLength).result.head))
    assertEquals(Vector.empty[String], run(Query(Json("[]").bind.jsonKeys).result.head))
    assertEquals(Vector("x", "y"), run(Query(Json("""{"x":1,"y":null}""").bind.jsonKeys).result.head))
    assertEquals(BigInt("18446744073709551615"),
      run(Query(BigInt("18446744073709551615").bind).result.head))
  }

  @Test def containsTreatsNeedleAsJson(): Unit = {
    val doc = Json("""{"numbers":[1,2],"name":"duck"}""").bind
    assertEquals(true, run(Query(doc.jsonContains(Json.number(2).bind)).result.head))
    assertEquals(true, run(Query(doc.jsonContains(Json.string("duck").bind)).result.head))
    assertEquals(false, run(Query(doc.jsonContains(Json.string("goose").bind)).result.head))
    assertEquals(true, run(Query(doc.jsonContains(Json("""{"numbers":[1]}""").bind)).result.head))
  }

  @Test def nullableInputsRemainNullableAndJsonNullRemainsPresent(): Unit = {
    val nullDocument = Option.empty[Json].bind
    val length: Rep[Option[BigInt]] = nullDocument.jsonArrayLength
    val keys: Rep[Option[Vector[String]]] = nullDocument.jsonKeys
    val pretty: Rep[Option[String]] = nullDocument.jsonPretty
    val structure: Rep[Option[Json]] = nullDocument.jsonStructure
    val kind: Rep[Option[String]] = nullDocument.jsonType
    val contains: Rep[Option[Boolean]] = Json("[1]").bind.jsonContains(Option.empty[Json].bind)
    val valid: Rep[Option[Boolean]] = jsonValid(Option.empty[String].bind)
    val parsed: Rep[Option[Json]] = jsonParse(Option.empty[String].bind)

    assertEquals(None, run(Query(length).result.head))
    assertEquals(None, run(Query(keys).result.head))
    assertEquals(None, run(Query(pretty).result.head))
    assertEquals(None, run(Query(structure).result.head))
    assertEquals(None, run(Query(kind).result.head))
    assertEquals(None, run(Query(contains).result.head))
    assertEquals(None, run(Query(valid).result.head))
    assertEquals(None, run(Query(parsed).result.head))
    assertEquals("NULL", run(Query(Json.nullValue.bind.jsonType).result.head))
    assertEquals(Json("\"NULL\""), run(Query(Json.nullValue.bind.jsonStructure).result.head))
  }

  @Test def validAndParseHaveDifferentInvalidInputBehavior(): Unit = {
    assertEquals(false, run(Query(jsonValid("{".bind)).result.head))
    assertEquals(true, run(Query(jsonValid("null".bind)).result.head))
    assertEquals(Json("[1,2]"), run(Query(jsonParse(" [ 1, 2 ] ".bind)).result.head))
    assertTrue(scala.util.Try(run(Query(jsonParse("{".bind)).result.head)).isFailure)
  }

  @Test def typeNamesCoverDuckDbJsonKinds(): Unit = {
    val samples = Vector(
      Json("[]") -> "ARRAY",
      Json("{}") -> "OBJECT",
      Json("true") -> "BOOLEAN",
      Json("-1") -> "BIGINT",
      Json("1") -> "UBIGINT",
      Json("1.25") -> "DOUBLE",
      Json.string("duck") -> "VARCHAR",
      Json.nullValue -> "NULL"
    )
    samples.foreach { case (json, expected) =>
      assertEquals(expected, run(Query(json.bind.jsonType).result.head))
    }
  }

  @Test def scalarPathsUseValidatedBoundSelectors(): Unit = {
    val doc = Json("""{"items":["duck",null],"a.b":{"x":1}}""").bind
    val count: Rep[Option[BigInt]] = doc.jsonArrayLength(JsonScalarPath("$.items"))
    val missing: Rep[Option[BigInt]] = doc.jsonArrayLength(JsonScalarPath("$.missing"))
    val keys: Rep[Option[Vector[String]]] = doc.jsonKeys(JsonScalarPath("$.\"a.b\""))
    val kind: Rep[Option[String]] = doc.jsonType(JsonScalarPath("/items/1"))
    val firstKind: Rep[Option[String]] = doc.jsonType(JsonScalarPath("$.items[0]"))

    assertEquals(Some(BigInt(2)), run(Query(count).result.head))
    assertEquals(None, run(Query(missing).result.head))
    assertEquals(Some(Vector("x")), run(Query(keys).result.head))
    assertEquals(Some("NULL"), run(Query(kind).result.head))
    assertEquals(Some("VARCHAR"), run(Query(firstKind).result.head))
    assertEquals(2, Query(count).result.statements.head.count(_ == '?'))
    assertTrue(scala.util.Try(JsonScalarPath("$.items[*]")).isFailure)
    assertTrue(scala.util.Try(JsonScalarPath("$.items[0:2]")).isFailure)
    assertEquals(None, run(Query(Option.empty[Json].bind.jsonType(JsonScalarPath("$.items"))).result.head))
  }

  @Test def jdbcTypesMatchThePublishedMappings(): Unit = {
    Using.resource(DriverManager.getConnection("jdbc:duckdb:")) { connection =>
      Using.resource(connection.prepareStatement(
        "SELECT json_array_length(?), json_keys(?), json_pretty(?), json_structure(?), json(?)"
      )) { statement =>
        statement.setString(1, "[1,2]")
        statement.setString(2, "{\"x\":1}")
        statement.setString(3, "[1]")
        statement.setString(4, "{\"x\":1}")
        statement.setString(5, " { \"x\" : 1 } ")
        Using.resource(statement.executeQuery()) { results =>
          val metadata = results.getMetaData
          assertEquals("UBIGINT", metadata.getColumnTypeName(1))
          assertEquals("VARCHAR[]", metadata.getColumnTypeName(2))
          assertEquals("VARCHAR", metadata.getColumnTypeName(3))
          assertEquals("JSON", metadata.getColumnTypeName(4))
          assertEquals("JSON", metadata.getColumnTypeName(5))
          assertTrue(results.next())
          assertEquals(classOf[java.math.BigInteger], results.getObject(1).getClass)
          assertEquals(BigInt(2).bigInteger, results.getObject(1))
          assertEquals(Vector("x"), results.getArray(2).getArray.asInstanceOf[Array[AnyRef]].toVector)
          assertTrue(results.getString(3).contains("\n"))
          assertEquals("{\"x\":\"UBIGINT\"}", results.getString(4))
          assertEquals("{\"x\":1}", results.getString(5))
        }
      }
    }
  }

  @Test def jdbcBoundPathsAndNeedlesPreserveNullMetadata(): Unit = {
    Using.resource(DriverManager.getConnection("jdbc:duckdb:")) { connection =>
      Using.resource(connection.prepareStatement(
        "SELECT json_array_length(?, ?), json_keys(?, ?), json_type(?, ?), json_contains(?, ?), json_valid(?)"
      )) { statement =>
        statement.setString(1, "{\"items\":[1,2]}")
        statement.setString(2, "$.items")
        statement.setString(3, "{\"nested\":{\"x\":1}}")
        statement.setString(4, "/nested")
        statement.setString(5, "{\"items\":[1,2]}")
        statement.setString(6, "$.items[0]")
        statement.setString(7, "{\"name\":\"duck\"}")
        statement.setString(8, "\"duck\"")
        statement.setString(9, "{")
        Using.resource(statement.executeQuery()) { results =>
          val metadata = results.getMetaData
          assertEquals("UBIGINT", metadata.getColumnTypeName(1))
          assertEquals("VARCHAR[]", metadata.getColumnTypeName(2))
          assertEquals("VARCHAR", metadata.getColumnTypeName(3))
          assertEquals("BOOLEAN", metadata.getColumnTypeName(4))
          assertEquals("BOOLEAN", metadata.getColumnTypeName(5))
          assertTrue(results.next())
          assertEquals(BigInt(2).bigInteger, results.getObject(1))
          assertEquals(Vector("x"), results.getArray(2).getArray.asInstanceOf[Array[AnyRef]].toVector)
          assertEquals("UBIGINT", results.getString(3))
          assertEquals(true, results.getBoolean(4))
          assertEquals(false, results.getBoolean(5))
        }
      }
      Using.resource(connection.prepareStatement("SELECT json_type(?, ?)")) { statement =>
        statement.setString(1, "{\"x\":1}")
        statement.setNull(2, java.sql.Types.VARCHAR)
        Using.resource(statement.executeQuery()) { results =>
          assertTrue(results.next())
          assertEquals(null, results.getObject(1))
          assertTrue(results.wasNull())
        }
      }
    }
  }
}
