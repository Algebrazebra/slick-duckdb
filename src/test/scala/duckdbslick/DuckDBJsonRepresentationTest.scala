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

  @Test def testAlternateRepresentationMutationsPreserveNulls(): Unit = {
    assumeTrue(DuckDBVersion.supportsJsonMutations(DuckDBProfile.getClass.getClassLoader))

    val source = LiteralColumn(AlternateJson("{}"))
    val setJsonNull = alternateRepresentation.column(source).jsonSet(
      JsonPath("$.a").bind,
      AlternateJson("null").bind
    )
    val setSqlNull = alternateRepresentation.column(source).jsonSet(
      JsonPath("$.a").bind,
      Option.empty[AlternateJson].bind
    )
    val removed = alternateRepresentation.column(source).jsonRemove(JsonPath("$").bind)
    val nullDocument = alternateRepresentation.optionalColumn(LiteralColumn(Option.empty[AlternateJson]))
      .jsonSet(JsonPath("$.a").bind, AlternateJson("1").bind)

    assertEquals(AlternateJson("""{"a":null}"""), run(Query(setJsonNull).result.head))
    assertEquals(None, run(Query(setSqlNull).result.head))
    assertEquals(None, run(Query(removed).result.head))
    assertEquals(None, run(Query(nullDocument).result.head))
  }

  @Test def storedAlternateDocumentComposesCreationMutationAndPatching(): Unit = {
    assumeTrue(DuckDBVersion.supportsJsonMutations(DuckDBProfile.getClass.getClassLoader))

    class Documents(tag: Tag) extends Table[AlternateJson](tag, "alternate_json_pipeline") {
      def document = column[AlternateJson]("document")
      def * = document
    }
    val documents = TableQuery[Documents]
    val projected = documents.map { row =>
      val doc = row.document
      val inserted = alternateRepresentation.column(doc).jsonInsert(
        JsonPath("$.added").bind,
        toJsonAs[AlternateJson, Int](2.bind)
      )
      val replaced = alternateRepresentation.column(inserted).jsonReplace(
        JsonPath("$.added").bind,
        toJsonAs[AlternateJson, Int](3.bind)
      )
      val patch = alternateRepresentation.column(doc).jsonMergePatchDiff(replaced)
      jsonMergePatchAs(doc, patch)
    }

    val result = run(for {
      _ <- documents.schema.create
      _ <- documents += AlternateJson("{}")
      value <- projected.result.head
    } yield value)
    assertEquals(AlternateJson("""{"added":3}"""), result)
  }

  @Test def testAlternateRepresentationPatchingResultAndNormalization(): Unit = {
    assumeTrue(DuckDBVersion.supportsJsonPatching(DuckDBProfile.getClass.getClassLoader))

    val source = LiteralColumn(AlternateJson("""{"b":2,"a":null}"""))
    val stripped: Rep[AlternateJson] = alternateRepresentation.column(source).jsonStripNulls
    val normalized: Rep[String] = alternateRepresentation.column(stripped).jsonNormalize
    val jsonNull: Rep[AlternateJson] =
      alternateRepresentation.column(LiteralColumn(AlternateJson("null"))).jsonStripNulls
    val sqlNull: Rep[Option[AlternateJson]] =
      alternateRepresentation.optionalColumn(LiteralColumn(Option.empty[AlternateJson])).jsonStripNulls
    val merged: Rep[AlternateJson] = alternateRepresentation.column(LiteralColumn(AlternateJson("{}")))
      .jsonDeepMerge(AlternateJson("{\"n\":3}").bind)
    val diff: Rep[AlternateJson] = alternateRepresentation.column(LiteralColumn(AlternateJson("{\"n\":1}")))
      .jsonMergePatchDiff(AlternateJson("{\"n\":2}").bind)

    assertEquals(AlternateJson("""{"b":2}"""), run(Query(stripped).result.head))
    assertEquals("""{"b":2}""", run(Query(normalized).result.head))
    assertEquals(AlternateJson("null"), run(Query(jsonNull).result.head))
    assertEquals(None, run(Query(sqlNull).result.head))
    assertEquals(AlternateJson("{\"n\":3}"), run(Query(merged).result.head))
    assertEquals(AlternateJson("{\"n\":2}"), run(Query(diff).result.head))
  }

  @Test def testAlternateRepresentationDeepMergeAcceptsMixedNullablePatches(): Unit = {
    assumeTrue(DuckDBVersion.supportsJsonPatching(DuckDBProfile.getClass.getClassLoader))

    val document = alternateRepresentation.optionalColumn(Option.empty[AlternateJson].bind)
    val requiredPatch = AlternateJson("""{"n":3}""").bind
    val nullPatch = Option.empty[AlternateJson].bind
    val nullablePatches: Seq[Rep[Option[AlternateJson]]] = Seq(nullPatch)
    val mixedPatches: Seq[JsonDeepMergePatchOf[AlternateJson]] = Seq(nullPatch, requiredPatch)
    val endingInNull: Rep[Option[AlternateJson]] = document.jsonDeepMerge(requiredPatch, nullPatch)
    val recovered: Rep[Option[AlternateJson]] = document.jsonDeepMerge(nullPatch, requiredPatch)
    val nullableSpread: Rep[Option[AlternateJson]] =
      document.jsonDeepMerge(nullPatch, nullablePatches *)
    val mixedSpread: Rep[Option[AlternateJson]] =
      document.jsonDeepMerge(requiredPatch, mixedPatches *)

    assertEquals(None, run(Query(endingInNull).result.head))
    assertEquals(Some(AlternateJson("""{"n":3}""")), run(Query(recovered).result.head))
    assertEquals(None, run(Query(nullableSpread).result.head))
    assertEquals(Some(AlternateJson("""{"n":3}""")), run(Query(mixedSpread).result.head))
  }

  @Test def testAlternateRepresentationFailsEarlyOnOlderDrivers(): Unit = {
    assumeTrue(!DuckDBVersion.supportsJsonMutations(DuckDBProfile.getClass.getClassLoader))

    val source = LiteralColumn(AlternateJson("{}"))
    val path = JsonPath("$.a").bind
    val failure = Try(alternateRepresentation.column(source).jsonSet(path, AlternateJson("1").bind)).failed.get
    assertTrue(failure.isInstanceOf[SlickException])
    assertTrue(failure.getMessage.contains("requires DuckDB JDBC 2.0 or newer"))

    val removeFailure = Try(alternateRepresentation.column(source).jsonRemove(path)).failed.get
    assertTrue(removeFailure.isInstanceOf[SlickException])
    assertTrue(removeFailure.getMessage.contains("requires DuckDB JDBC 2.0 or newer"))

    val normalizeFailure = Try(alternateRepresentation.column(source).jsonNormalize).failed.get
    assertTrue(normalizeFailure.isInstanceOf[SlickException])
    assertTrue(normalizeFailure.getMessage.contains("JSON patching and normalization"))

    val stripFailure = Try(alternateRepresentation.column(source).jsonStripNulls).failed.get
    assertTrue(stripFailure.isInstanceOf[SlickException])
    assertTrue(stripFailure.getMessage.contains("JSON patching and normalization"))

    val mergeFailure = Try(alternateRepresentation.column(source)
      .jsonDeepMerge(AlternateJson("{}").bind)).failed.get
    assertTrue(mergeFailure.isInstanceOf[SlickException])
    val diffFailure = Try(alternateRepresentation.column(source)
      .jsonMergePatchDiff(AlternateJson("{}").bind)).failed.get
    assertTrue(diffFailure.isInstanceOf[SlickException])
  }
}
