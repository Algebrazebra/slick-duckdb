package examples

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.{Json, JsonScalarPath}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

class DuckDBJsonLiftedRepresentationTest {
  private case class AppJson(text: String)

  private implicit val representation: JsonRepresentation[AppJson] =
    jsonRepresentation[AppJson](_.text, AppJson(_))
  private implicit val mapping: BaseColumnType[AppJson] = representation.jdbcType

  private def run[A](action: DBIO[A]): A = {
    val db = Database.forURL("jdbc:duckdb:", driver = "org.duckdb.DuckDBDriver")
    try Await.result(db.run(action), 10.seconds)
    finally db.close()
  }

  @Test def extractionKeepsTheRepresentationThroughBoundArrows(): Unit = {
    val raw = """{"species":[{"name":"duck","empty":null}]}"""
    val document = representation.column(AppJson(raw).bind)
    val first = JsonScalarPath("$.species[0]").bind
    val name = JsonScalarPath("/name").bind
    val empty = JsonScalarPath("$.empty").bind
    val missing = JsonScalarPath("$.missing").bind

    val firstSpecies: Rep[Option[AppJson]] = document -> first
    val species = representation.optionalColumn(firstSpecies)
    val chained: Rep[Option[AppJson]] = species -> name
    val text: Rep[Option[String]] = species ->> name
    val extractedText: Rep[Option[String]] = species.jsonExtractText(name)
    val scalar: Rep[Option[AppJson]] = species.jsonValue(name)
    val nonScalar: Rep[Option[AppJson]] = document.jsonValue(first)
    val jsonNull: Rep[Option[AppJson]] = species.jsonExtract(empty)
    val absent: Rep[Option[AppJson]] = species.jsonExtract(missing)
    val exists: Rep[Boolean] = document.jsonExists(first)
    val nullableExists: Rep[Option[Boolean]] = species.jsonExists(empty)

    val query = Query((chained, text, extractedText, scalar, nonScalar, jsonNull, absent, exists, nullableExists))
    val sql = query.result.statements.head
    assertTrue(sql, sql.contains("((? -> ?) -> ?)"))
    assertTrue(sql, sql.contains("((? -> ?) ->> ?)"))
    assertTrue(sql, sql.toLowerCase.contains("cast(json_value("))
    val quotedDuck = Some(AppJson("\"duck\""))
    assertEquals(
      (quotedDuck, Some("duck"), Some("duck"), quotedDuck, None, Some(AppJson("null")), None, true, Some(true)),
      run(query.result.head)
    )
  }

  @Test def nullableExtractionHandlesSqlNullDocumentsAndPaths(): Unit = {
    val root = JsonScalarPath("$").bind
    val sqlNull = representation.optionalColumn(Option.empty[AppJson].bind)
    val jsonNull = representation.column(AppJson("null").bind)
    val nullPath = Option.empty[JsonScalarPath].bind

    val sqlNullExtracted: Rep[Option[AppJson]] = sqlNull -> root
    val jsonNullExtracted: Rep[Option[AppJson]] = jsonNull -> root
    val jsonNullText: Rep[Option[String]] = jsonNull ->> root
    val nullPathExtracted: Rep[Option[AppJson]] = jsonNull.jsonExtract(nullPath)
    val nullPathExists: Rep[Option[Boolean]] = jsonNull.jsonExists(nullPath)
    val nullDocumentExists: Rep[Option[Boolean]] = sqlNull.jsonExists(root)

    assertEquals(
      (None, Some(AppJson("null")), None, None, None, None),
      run(Query((
        sqlNullExtracted, jsonNullExtracted, jsonNullText, nullPathExtracted, nullPathExists, nullDocumentExists
      )).result.head)
    )
  }

  @Test def scalarFunctionsUseTheRepresentationAndNativeResultTypes(): Unit = {
    val document = representation.column(AppJson("""{"a":[1,2],"name":"duck"}""").bind)
    val optional = representation.optionalColumn(Option.empty[AppJson].bind)
    val arrayPath = JsonScalarPath("/a")

    val structure: Rep[AppJson] = document.jsonStructure
    val nullStructure: Rep[Option[AppJson]] = optional.jsonStructure
    val contains: Rep[Boolean] = document.jsonContains(AppJson("2").bind)
    val nullNeedle: Rep[Option[Boolean]] = document.jsonContains(Option.empty[AppJson].bind)
    val length: Rep[BigInt] = document.jsonArrayLength
    val pathLength: Rep[Option[BigInt]] = document.jsonArrayLength(arrayPath)
    val keys: Rep[Seq[String]] = document.jsonKeys
    val pathKeys: Rep[Option[Seq[String]]] = document.jsonKeys(JsonScalarPath("$"))
    val pretty: Rep[String] = document.jsonPretty
    val kind: Rep[String] = document.jsonType
    val pathKind: Rep[Option[String]] = document.jsonType(arrayPath)
    val nullKind: Rep[Option[String]] = optional.jsonType(arrayPath)

    assertEquals(
      (AppJson("""{"a":["UBIGINT"],"name":"VARCHAR"}"""), None, true, None),
      run(Query((structure, nullStructure, contains, nullNeedle)).result.head)
    )
    assertEquals(
      (BigInt(0), Some(BigInt(2)), Seq("a", "name"), Some(Seq("a", "name")), "OBJECT", Some("ARRAY"), None),
      run(Query((length, pathLength, keys, pathKeys, kind, pathKind, nullKind)).result.head)
    )
    assertTrue(run(Query(pretty).result.head).contains("\n"))
  }

  @Test def parseSelectsAnAlternateResultAndPreservesTextNullability(): Unit = {
    val raw = " { \"a\" : 1 } ".bind
    val default: Rep[Json] = jsonParse(raw)
    val parsed: Rep[AppJson] = jsonParseAs[AppJson](raw)
    val jsonNull: Rep[Option[AppJson]] = jsonParseAs[AppJson](Option("null").bind)
    val sqlNull: Rep[Option[AppJson]] = jsonParseAs[AppJson](Option.empty[String].bind)

    assertEquals(
      (Json("""{"a":1}"""), AppJson("""{"a":1}"""), Some(AppJson("null")), None),
      run(Query((default, parsed, jsonNull, sqlNull)).result.head)
    )
    assertTrue(Try(run(Query(jsonParseAs[AppJson]("{".bind)).result.head)).isFailure)
  }
}
