package examples

import duckdbslick.DuckDBJsonSupport
import duckdbslick.types.JsonCodec
import org.junit.Assert.assertEquals
import org.junit.Test

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

private final case class AppJson(text: String)

private object AppProfile extends DuckDBJsonSupport[AppJson] {
  protected val jsonCodec: JsonCodec[AppJson] =
    JsonCodec[AppJson](_.text.stripPrefix("json:"), text => AppJson(s"json:$text"))
}

class DuckDBJsonProfileTest {
  import AppProfile.api.*
  import ExecutionContext.Implicits.global

  private def run[A](action: DBIO[A]): A = {
    val db = Database.forURL("jdbc:duckdb:", driver = "org.duckdb.DuckDBDriver")
    try Await.result(db.run(action.withPinnedSession), 10.seconds)
    finally db.close()
  }

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
}
