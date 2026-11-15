package examples

import duckdbslick.{DuckDBJsonPlainSqlSupport, DuckDBJsonSupport, DuckDBVersion}
import duckdbslick.types.{Json, JsonCodec, JsonPath, JsonScalarPath, JsonTransformCodec}
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

private final case class AppJson(text: String)

private object AppProfile extends DuckDBJsonSupport[AppJson] with DuckDBJsonPlainSqlSupport[AppJson] {
  protected val jsonCodec: JsonCodec[AppJson] =
    JsonCodec[AppJson](_.text.stripPrefix("json:"), text => AppJson(s"json:$text"))

  import api.*

  def run[A](action: DBIO[A]): A = {
    val db = Database.forURL("jdbc:duckdb:", driver = "org.duckdb.DuckDBDriver")
    try Await.result(db.run(action.withPinnedSession), 10.seconds)
    finally db.close()
  }
}

class DuckDBJsonProfileTest {
  import AppProfile.api.*
  import AppProfile.run
  import ExecutionContext.Implicits.global

  @Test def profileSelectsJsonForColumnsAndCreation(): Unit = {
    class Documents(tag: Tag) extends Table[(Int, Option[AppJson])](tag, "profile_json_documents") {
      def id = column[Int]("id")
      def document = column[Option[AppJson]]("document")
      def * = (id, document)
    }
    val documents = TableQuery[Documents]
    val rows = Seq((1, Some(AppJson("json:null"))), (2, None))
    val stored = run(for {
      _ <- documents.schema.create
      _ <- documents ++= rows
      loaded <- documents.sortBy(_.id).result
    } yield loaded)
    assertEquals(rows, stored)

    val scalar: Rep[AppJson] = toJson("duck".bind)
    val missing: Rep[Option[AppJson]] = toJson(Option.empty[String].bind)
    val array: Rep[AppJson] = jsonArray(AppJson("json:null").bind, 1.bind)
    val obj: Rep[AppJson] = jsonObject("key".bind -> AppJson("json:null").bind)
    val patched: Rep[AppJson] = jsonMergePatch(obj, AppJson("""json:{"key":2}""").bind)
    assertEquals(
      (AppJson("""json:"duck""""), None, AppJson("json:[null,1]"),
        AppJson("""json:{"key":null}"""), AppJson("""json:{"key":2}""")),
      run(Query((scalar, missing, array, obj, patched)).result.head)
    )
  }

  @Test def mutationsAndPatchesUseTheColumnType(): Unit = {
    assumeTrue(DuckDBVersion.supportsJsonMutations(AppProfile.getClass.getClassLoader))

    class Documents(tag: Tag) extends Table[AppJson](tag, "profile_json_mutations") {
      def document = column[AppJson]("document")
      def * = document
    }
    val documents = TableQuery[Documents]
    val projected = documents.map { row =>
      val path = JsonPath("$.value").bind
      val inserted: Rep[AppJson] = row.document.jsonInsert(path, toJson(2.bind))
      val replaced: Rep[AppJson] = inserted.jsonReplace(path, toJson(3.bind))
      val patch: Rep[AppJson] = row.document.jsonMergePatchDiff(replaced)
      val restored = jsonMergePatch(row.document, patch)
      val patches: Seq[JsonDeepMergePatch] = Seq(
        Option.empty[AppJson].bind,
        toJson(4.bind)
      )
      val merged = restored.jsonDeepMerge(restored, patches *)
      (restored, merged, restored.jsonRemove(JsonPath("$").bind))
    }
    val result = run(for {
      _ <- documents.schema.create
      _ <- documents += AppJson("json:{}")
      value <- projected.result.head
    } yield value)
    assertEquals((AppJson("""json:{"value":3}"""), Some(AppJson("json:4")), None), result)
  }

  @Test def extractionChainsOnTheColumnType(): Unit = {
    val document = AppJson("""json:{"species":[{"name":"duck","empty":null}]}""").bind
    val first = JsonScalarPath("$.species[0]").bind
    val name = JsonScalarPath("/name").bind
    val empty = JsonScalarPath("$.empty").bind
    val missing = JsonScalarPath("$.missing").bind

    val species: Rep[Option[AppJson]] = document -> first
    val chained: Rep[Option[AppJson]] = species -> name
    val text: Rep[Option[String]] = species ->> name
    val scalar: Rep[Option[AppJson]] = species.jsonValue(name)
    val jsonNull: Rep[Option[AppJson]] = species.jsonExtract(empty)
    val absent: Rep[Option[AppJson]] = species.jsonExtract(missing)
    val exists: Rep[Option[Boolean]] = species.jsonExists(empty)
    val duck = Some(AppJson("""json:"duck""""))
    assertEquals((duck, Some("duck"), duck, Some(AppJson("json:null")), None, Some(true)),
      run(Query((chained, text, scalar, jsonNull, absent, exists)).result.head))
  }

  @Test def parsingScalarsAndAggregatesUseTheProfileMapping(): Unit = {
    val parsed: Rep[AppJson] = jsonParse(" { \"a\" : 1 } ".bind)
    val empty: Rep[Option[AppJson]] = jsonParse(Option.empty[String].bind)
    val structure: Rep[AppJson] = parsed.jsonStructure
    val contains: Rep[Boolean] = parsed.jsonContains(AppJson("json:1").bind)
    assertEquals((AppJson("""json:{"a":1}"""), None, AppJson("""json:{"a":"UBIGINT"}"""), true),
      run(Query((parsed, empty, structure, contains)).result.head))

    val array: Rep[Option[AppJson]] = Query(2.bind).jsonGroupArray
    val obj: Rep[Option[AppJson]] = Query(("a".bind, 2.bind)).jsonGroupObject
    val merged: Rep[Option[AppJson]] = Query(parsed).jsonGroupStructure
    val noRows: Rep[Option[AppJson]] = Query(2.bind).filter(_ => false.bind).jsonGroupArray
    assertEquals((Some(AppJson("json:[2]")), Some(AppJson("""json:{"a":2}""")),
      Some(AppJson("""json:{"a":"UBIGINT"}""")), None),
      run(Query((array, obj, merged, noRows)).result.head))
  }

  @Test def profileJsonComposesWithTransformsAndArrayProjections(): Unit = {
    case class Count(value: Int)
    val codec = JsonTransformCodec.struct[Count](Json("""{"count":"INTEGER"}""")) { row =>
      Count(row.getAttributes()(0).asInstanceOf[Int])
    }
    val document: Rep[AppJson] = jsonObject("count".bind -> 5.bind)
    val transformed = document.jsonTransformStrict(codec)
    val absent = Option.empty[AppJson].bind.jsonTransform(codec)
    assertEquals(Some(Count(5)), run(Query(transformed).result.head))
    assertEquals(None, run(Query(absent).result.head))

    val elements: Rep[AppJson] = jsonArray(5.bind, AppJson("json:null").bind).jsonArrayElements
    assertEquals(Seq(AppJson("json:5"), AppJson("json:null")), run(Query(elements).result))
  }
}
