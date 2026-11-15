package examples

import examples.AppProfile.api.*
import examples.AppProfile.plainJson.*
import examples.AppProfile.run
import duckdbslick.types.{JsonPath, JsonTableRow}
import org.junit.Assert.{assertEquals, assertSame, assertTrue}
import org.junit.Test
import slick.SlickException

import scala.concurrent.ExecutionContext
import scala.util.Try

class DuckDBJsonPlainSqlTest {
  import ExecutionContext.Implicits.global

  @Test def plainAndLiftedQueriesUseTheSameProfileCodec(): Unit = {
    class Documents(tag: Tag) extends Table[AppJson](tag, "plain_json_documents") {
      def document = column[AppJson]("document")
      def * = document
    }
    val documents = TableQuery[Documents]
    val document = AppJson("json:42")
    val result = run(for {
      _ <- documents.schema.create
      _ <- sqlu"insert into plain_json_documents values (cast($document as json))"
      lifted <- documents.result.head
      plain <- sql"select 7, to_json(document), 'tail' from plain_json_documents"
        .as[(Int, AppJson, String)].head
    } yield (lifted, plain))
    assertEquals((document, (7, document, "tail")), result)
  }

  @Test def optionalPlainSqlDistinguishesSqlNullAndJsonNull(): Unit = {
    val jsonNull: Option[AppJson] = Some(AppJson("json:null"))
    val sqlNull = Option.empty[AppJson]
    val query = sql"select cast($jsonNull as json), cast($sqlNull as json)"
      .as[(Option[AppJson], Option[AppJson])]
    assertEquals((jsonNull, sqlNull), run(query.head))
    val failure = Try(run(sql"select cast(null as json)".as[AppJson].head)).failed.get
    assertTrue(failure.isInstanceOf[SlickException])
  }

  @Test def plainSqlPropagatesDecoderFailure(): Unit = {
    import duckdbslick.{DuckDBJsonPlainSqlSupport, DuckDBJsonSupport}
    import duckdbslick.types.JsonCodec

    val unsupported = new IllegalArgumentException("Unsupported JSON value")
    object RejectingProfile extends DuckDBJsonSupport[AppJson] with DuckDBJsonPlainSqlSupport[AppJson] {
      protected val jsonCodec: JsonCodec[AppJson] = JsonCodec(_ => "null", _ => throw unsupported)
    }
    val reader = RejectingProfile.plainJson.jsonOptionGetResult
    val query = sql"select cast('null' as json)".as[Option[AppJson]](reader)
    assertSame(unsupported, Try(run(query.head)).failed.get)
    assertEquals(None, run(sql"select cast(null as json)".as[Option[AppJson]](reader).head))
  }

  @Test def tableRowsUseTheProfileCodecAndBindings(): Unit = {
    val document = AppJson("""json:{"n":null,"number":2}""")
    val path = JsonPath("$")
    val rows = run(sql"""SELECT jt.key, jt.value, jt.type, jt.atom, jt.id, jt.parent,
                                jt.fullkey, jt.path, jt.json, jt.root, jt.rowid
                         FROM json_each($document, $path) AS jt
                         ORDER BY jt.rowid""".as[JsonTableRow[AppJson]])
    assertEquals(Seq(
      (Some("n"), AppJson("json:null"), None, document),
      (Some("number"), AppJson("json:2"), Some(AppJson("json:2")), document)
    ), rows.map(row => (row.key, row.value, row.atom, row.inputJson)))
  }
}
