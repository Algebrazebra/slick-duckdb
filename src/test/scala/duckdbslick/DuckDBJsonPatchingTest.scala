package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.Json
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Assume.assumeTrue
import org.junit.Test
import slick.SlickException

import scala.util.Try

class DuckDBJsonPatchingTest {
  private val supported =
    DuckDBVersion.supportsJsonPatching(DuckDBProfile.getClass.getClassLoader)

  @Test def deepMergeBindsOrderedPatches(): Unit = {
    assumeTrue(supported)

    val merged: Rep[Json] = Json("""{"a":{"x":1},"items":[1]}""").bind
      .jsonDeepMerge(
        Json("""{"a":{"y":2},"items":[2]}""").bind,
        Json("""{"a":{"x":null,"z":3}}""").bind
      )
    val query = Query(merged)
    val sql = query.result.statements.head.toLowerCase

    assertTrue(sql, sql.contains("json_deep_merge("))
    assertEquals(3, sql.count(_ == '?'))
    assertEquals("""{"a":{"x":1,"y":2,"z":3},"items":[2]}""",
      run(Query(merged.jsonNormalize).result.head))
  }

  @Test def deepMergeAcceptsMixedNullablePatches(): Unit = {
    assumeTrue(supported)

    val document = Json("""{"a":1}""").bind
    val requiredPatch = Json("""{"a":2}""").bind
    val nullablePatch = Option(Json("""{"b":3}""")).bind
    val required: Rep[Json] = document.jsonDeepMerge(requiredPatch)
    val nullable: Rep[Option[Json]] = document.jsonDeepMerge(nullablePatch)
    val mixed: Rep[Option[Json]] = document.jsonDeepMerge(requiredPatch, nullablePatch)
    val reversed: Rep[Option[Json]] = document.jsonDeepMerge(nullablePatch, requiredPatch)
    val later: Rep[Option[Json]] = document.jsonDeepMerge(
      requiredPatch, requiredPatch, nullablePatch
    )
    val requiredSequence: Seq[Rep[Json]] = Seq(requiredPatch)
    val nullableSequence: Seq[Rep[Option[Json]]] = Seq(nullablePatch)
    val mixedSequence: Seq[JsonDeepMergePatch] = Seq(nullablePatch, requiredPatch)
    val reversedSequence: Seq[JsonDeepMergePatch] = Seq(requiredPatch)
    val requiredSpread: Rep[Json] = document.jsonDeepMerge(
      requiredPatch, requiredSequence: _*
    )
    val nullableSpread: Rep[Option[Json]] = document.jsonDeepMerge(
      nullablePatch, nullableSequence: _*
    )
    val mixedSpread: Rep[Option[Json]] = document.jsonDeepMerge(
      requiredPatch, mixedSequence: _*
    )
    val reversedSpread: Rep[Option[Json]] = document.jsonDeepMerge(
      nullablePatch, reversedSequence: _*
    )
    val sql = Query(mixed).result.statements.head.toLowerCase

    assertEquals(Json("""{"a":2}"""), run(Query(required).result.head))
    assertEquals(Some(Json("""{"a":1,"b":3}""")), run(Query(nullable).result.head))
    assertEquals(Json("""{"a":2}"""), run(Query(requiredSpread).result.head))
    assertEquals(Some("""{"a":1,"b":3}"""),
      run(Query(nullableSpread.jsonNormalize).result.head))
    assertEquals(Some("""{"a":2,"b":3}"""),
      run(Query(mixedSpread.jsonNormalize).result.head))
    assertEquals(Some("""{"a":2,"b":3}"""),
      run(Query(reversedSpread.jsonNormalize).result.head))
    assertTrue(sql, sql.contains("json_deep_merge("))
    assertEquals(3, sql.count(_ == '?'))
    assertEquals(Some("""{"a":2,"b":3}"""),
      run(Query(mixed.jsonNormalize).result.head))
    assertEquals(Some("""{"a":2,"b":3}"""),
      run(Query(reversed.jsonNormalize).result.head))
    assertEquals(Some("""{"a":2,"b":3}"""),
      run(Query(later.jsonNormalize).result.head))
  }

  @Test def deepMergeSqlNullPatchDependsOnPosition(): Unit = {
    assumeTrue(supported)

    val document = Json("""{"a":1}""").bind
    val before = Json("""{"b":2}""").bind
    val after = Json("""{"c":3}""").bind
    val nullPatch = Option.empty[Json].bind
    val nullFirst: Rep[Option[Json]] = document.jsonDeepMerge(nullPatch, after)
    val nullMiddle: Rep[Option[Json]] = document.jsonDeepMerge(before, nullPatch, after)
    val nullLast: Rep[Option[Json]] = document.jsonDeepMerge(before, nullPatch)

    assertEquals(Some(Json("""{"c":3}""")), run(Query(nullFirst).result.head))
    assertEquals(Some(Json("""{"c":3}""")), run(Query(nullMiddle).result.head))
    assertEquals(None, run(Query(nullLast).result.head))
  }

  @Test def normalizeSortsObjectKeysWithinArrays(): Unit = {
    assumeTrue(supported)

    val normalized: Rep[String] = Json("""{"z":[{"b":2,"a":1},{"d":4,"c":3}],"a":0}""")
      .bind.jsonNormalize
    val query = Query(normalized)
    val sql = query.result.statements.head.toLowerCase

    assertTrue(sql, sql.contains("json_normalize("))
    assertEquals(1, sql.count(_ == '?'))
    assertEquals("""{"a":0,"z":[{"a":1,"b":2},{"c":3,"d":4}]}""",
      run(query.result.head))
  }

  @Test def deepMergeDistinguishesSqlNullAndJsonNull(): Unit = {
    assumeTrue(supported)

    val document = Json("""{"a":1}""").bind
    val nullDocument: Rep[Json] = Option.empty[Json].bind.jsonDeepMerge(document)
    val nullPatch: Rep[Option[Json]] = document.jsonDeepMerge(Option.empty[Json].bind)
    val jsonNullMember: Rep[Json] = document.jsonDeepMerge(Json("""{"a":null}""").bind)
    val jsonNullPatch: Rep[Json] = document.jsonDeepMerge(Json.nullValue.bind)
    val nullablePatches: Rep[Option[Json]] = document.jsonDeepMerge(
      Option(Json("""{"b":2}""")).bind,
      Option(Json("""{"c":3}""")).bind
    )

    assertEquals(Json("""{"a":1}"""), run(Query(nullDocument).result.head))
    assertEquals(None, run(Query(nullPatch).result.head))
    assertEquals(Json("""{"a":1}"""), run(Query(jsonNullMember).result.head))
    assertEquals(Json("""{"a":1}"""), run(Query(jsonNullPatch).result.head))
    assertEquals(Some("""{"a":1,"b":2,"c":3}"""),
      run(Query(nullablePatches.jsonNormalize).result.head))
  }

  @Test def patchDiffReconstructsModifiedDocument(): Unit = {
    assumeTrue(supported)

    val original = Json("""{"gone":1,"nested":{"old":2},"same":3}""").bind
    val modified = Json("""{"nested":{"new":4},"same":3,"added":5}""").bind
    val diff: Rep[Json] = original.jsonMergePatchDiff(modified)
    val sql = Query(diff).result.statements.head.toLowerCase
    val patchText = run(Query(diff.jsonNormalize).result.head)
    val restored = jsonMergePatch(original, diff)

    assertTrue(sql, sql.contains("json_merge_patch_diff("))
    assertEquals(2, sql.count(_ == '?'))
    assertEquals("""{"added":5,"gone":null,"nested":{"new":4,"old":null}}""", patchText)
    assertEquals(run(Query(modified.jsonNormalize).result.head),
      run(Query(restored.jsonNormalize).result.head))
  }

  @Test def patchDiffNullabilityFollowsModifiedValue(): Unit = {
    assumeTrue(supported)

    val original = Json("""{"a":1}""").bind
    val nullOriginal: Rep[Json] = Option.empty[Json].bind
      .jsonMergePatchDiff(Json("""{"b":2}""").bind)
    val nullModified: Rep[Option[Json]] = original
      .jsonMergePatchDiff(Option.empty[Json].bind)
    val jsonNull: Rep[Json] = original.jsonMergePatchDiff(Json.nullValue.bind)

    assertEquals(Json("""{"b":2}"""), run(Query(nullOriginal).result.head))
    assertEquals(None, run(Query(nullModified).result.head))
    assertEquals(Json("null"), run(Query(jsonNull).result.head))
  }

  @Test def stripNullsKeepsArrayElementsAndDistinguishesNulls(): Unit = {
    assumeTrue(supported)

    val stripped: Rep[Json] = Json("""{"z":null,"a":{"drop":null,"keep":1},"list":[null,{"x":null,"y":2}]}""")
      .bind.jsonStripNulls
    val sql = Query(stripped).result.statements.head.toLowerCase
    val nullInput: Rep[Option[Json]] = Option.empty[Json].bind.jsonStripNulls
    val jsonNull: Rep[Json] = Json.nullValue.bind.jsonStripNulls

    assertTrue(sql, sql.contains("json_strip_nulls("))
    assertEquals(1, sql.count(_ == '?'))
    assertEquals("""{"a":{"keep":1},"list":[null,{"y":2}]}""",
      run(Query(stripped.jsonNormalize).result.head))
    assertEquals(None, run(Query(nullInput).result.head))
    assertEquals(Json.nullValue, run(Query(jsonNull).result.head))
  }

  @Test def normalizeDistinguishesSqlNullAndJsonNull(): Unit = {
    assumeTrue(supported)

    val sqlNull: Rep[Option[String]] = Option.empty[Json].bind.jsonNormalize
    val jsonNull: Rep[String] = Json.nullValue.bind.jsonNormalize

    assertEquals(None, run(Query(sqlNull).result.head))
    assertEquals("null", run(Query(jsonNull).result.head))
  }

  @Test def newFunctionsRejectOlderDriversBeforeExecution(): Unit = {
    assumeTrue(!supported)

    val json = Json("{}").bind
    val attempts = Seq(
      () => json.jsonDeepMerge(json),
      () => json.jsonMergePatchDiff(json),
      () => json.jsonNormalize,
      () => json.jsonStripNulls
    )
    attempts.foreach { attempt =>
      val failure = Try(attempt()).failed.get
      assertTrue(failure.isInstanceOf[SlickException])
      assertTrue(failure.getMessage.contains("JSON patching and normalization"))
    }
  }
}
