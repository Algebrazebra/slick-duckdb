package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertSame, assertTrue}
import org.junit.Test
import slick.SlickException
import slick.jdbc.{GetResult, SetParameter}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

private final case class PlainSqlJson(number: Option[Int])

class DuckDBJsonPlainSqlTest {

  private val representation = jsonRepresentation[PlainSqlJson](
    _.number.fold("null")(_.toString),
    text => PlainSqlJson(if (text == "null") None else Some(text.toInt))
  )

  @Test def plainSqlAndLiftedQueriesUseTheSameCodec(): Unit = {
    implicit val jdbcType: BaseColumnType[PlainSqlJson] = representation.jdbcType
    implicit val set: SetParameter[PlainSqlJson] = representation.setParameter
    implicit val get: GetResult[PlainSqlJson] = representation.getResult

    class Documents(tag: Tag) extends Table[PlainSqlJson](tag, "plain_json_documents") {
      def document = column[PlainSqlJson]("document")
      def * = document
    }
    val documents = TableQuery[Documents]
    val document = PlainSqlJson(Some(42))
    val result = run(for {
      _ <- documents.schema.create
      _ <- sqlu"insert into plain_json_documents values (cast($document as json))"
      lifted <- documents.result.head
      plain <- sql"select 7, to_json(document), 'tail' from plain_json_documents"
        .as[(Int, PlainSqlJson, String)].head
    } yield (lifted, plain))

    assertEquals((document, (7, document, "tail")), result)
  }

  @Test def optionalParametersAndResultsKeepJsonNullDistinctFromSqlNull(): Unit = {
    implicit val set: SetParameter[Option[PlainSqlJson]] = representation.setOptionalParameter
    implicit val get: GetResult[Option[PlainSqlJson]] = representation.getOptionalResult

    val jsonNull: Option[PlainSqlJson] = Some(PlainSqlJson(None))
    val sqlNull = Option.empty[PlainSqlJson]
    val value: Option[PlainSqlJson] = Some(PlainSqlJson(Some(9)))
    val query = sql"""select to_json(cast($jsonNull as json)),
                       to_json(cast($sqlNull as json)), to_json(cast($value as json))"""
      .as[(Option[PlainSqlJson], Option[PlainSqlJson], Option[PlainSqlJson])]

    assertEquals((jsonNull, sqlNull, value), run(query.head))
  }

  @Test def requiredResultFailsForSqlNull(): Unit = {
    val failure = Try(run(sql"select cast(null as json)"
      .as[PlainSqlJson](representation.getResult).head)).failed.get

    assertTrue(failure.isInstanceOf[SlickException])
    assertTrue(failure.getMessage.contains("SQL NULL"))
    assertEquals(PlainSqlJson(None),
      run(sql"select cast('null' as json)".as[PlainSqlJson](representation.getResult).head))
  }

  @Test def optionalReaderPropagatesDecoderFailure(): Unit = {
    val unsupported = new IllegalArgumentException("Unsupported JSON value")
    val rejecting = jsonRepresentation[PlainSqlJson](_ => "null", _ => throw unsupported)
    val query = sql"select cast('null' as json)".as[Option[PlainSqlJson]](rejecting.getOptionalResult)

    assertSame(unsupported, Try(run(query.head)).failed.get)
    assertEquals(None,
      run(sql"select cast(null as json)".as[Option[PlainSqlJson]](rejecting.getOptionalResult).head))
  }
}
