package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.api.JsonTransformInput
import duckdbslick.types.{Json, JsonTransformCodec}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class DuckDBJsonTransformTest {
  final case class Person(age: Option[Int])
  final case class Reading(label: Option[String], samples: Vector[Option[Int]])
  final case class AlternateJson(value: Json)

  private val person: JsonTransformCodec[Person] = JsonTransformCodec.struct[Person](Json("""{"age":"INTEGER"}""")) { row =>
    Person(Option(row.getAttributes()(0)).map(_.asInstanceOf[Int]))
  }

  @Test def typedStructProjectionUsesBoundStructure(): Unit = {
    val transformed =
      Json("""{"age":42}""").bind.jsonTransform(person)
    val typed: Rep[Option[Person]] = transformed
    val query = Query(transformed)
    val sql = query.result.statements.head.toLowerCase
    assertTrue(sql, sql.contains("json_transform("))
    assertEquals(2, sql.count(_ == '?'))
    assertEquals(Some(Person(Some(42))), run(query.result.head))
  }

  @Test def nullableColumnProjectsThroughQueryCompilation(): Unit = {
    class Documents(tag: Tag) extends Table[(Int, Option[Json])](tag, "transform_documents") {
      def id = column[Int]("id")
      def document = column[Option[Json]]("document")
      def * = (id, document)
    }
    val documents = TableQuery[Documents]
    val transformed = documents.sortBy(_.id).map(_.document.jsonTransform(person))
    val action = for {
      _ <- documents.schema.create
      _ <- documents ++= Seq(
        (1, Some(Json("{\"age\":7}"))),
        (2, Some(Json.nullValue)),
        (3, None)
      )
      result <- transformed.result
    } yield result
    assertEquals(Seq(Some(Person(Some(7))), None, None), run(action))
  }

  @Test def alternateSqlJsonInputUsesSameResultCodec(): Unit = {
    implicit val alternateJdbcType: BaseColumnType[AlternateJson] =
      MappedColumnType.base[AlternateJson, Json](_.value, AlternateJson(_))
    implicit val alternateInput: JsonTransformInput[AlternateJson] =
      new JsonTransformInput(alternateJdbcType)
    val converted = AlternateJson(Json("{\"age\":9}")).bind.jsonTransform(person)
    val nullable = Option.empty[AlternateJson].bind.jsonTransform(person)
    val typed: Rep[Option[Person]] = converted
    assertEquals(Some(Person(Some(9))), run(Query(converted).result.head))
    assertEquals(None, run(Query(nullable).result.head))
  }

  @Test def sharedRepresentationTransformsRequiredAndNullableInputs(): Unit = {
    val representation = jsonRepresentation[AlternateJson](_.value.value, text => AlternateJson(Json(text)))
    implicit val alternateJdbcType: BaseColumnType[AlternateJson] = representation.jdbcType

    val input = AlternateJson(Json("""{"age":12}"""))
    val transformed = representation.column(input.bind).jsonTransform(person)
    val strict = representation.optionalColumn(Option(input).bind).jsonTransformStrict(person)
    val jsonNull = representation.optionalColumn(Option(AlternateJson(Json.nullValue)).bind)
      .jsonTransform(person)
    val sqlNull = representation.optionalColumn(Option.empty[AlternateJson].bind)
      .jsonTransformStrict(person)
    val typed: Rep[Option[Person]] = transformed

    assertEquals(Some(Person(Some(12))), run(Query(transformed).result.head))
    assertEquals(Some(Person(Some(12))), run(Query(strict).result.head))
    assertEquals(None, run(Query(jsonNull).result.head))
    assertEquals(None, run(Query(sqlNull).result.head))
  }

  @Test def nestedListsPreserveFieldOrderAndNullElements(): Unit = {
    val readingCodec: JsonTransformCodec[Reading] =
      JsonTransformCodec.struct[Reading](Json("""{"label":"VARCHAR","samples":["INTEGER"]}""")) { row =>
        val fields = row.getAttributes()
        Reading(Option(fields(0)).map(_.asInstanceOf[String]),
          JsonTransformCodec.listElements(fields(1).asInstanceOf[java.sql.Array])(
            _.asInstanceOf[Int]
          ))
      }
    val transformed =
      Json("""{"label":"first","samples":[1,null,3]}""").bind.jsonTransform(readingCodec)
    val typed: Rep[Option[Reading]] = transformed
    assertEquals(Some(Reading(Some("first"), Vector(Some(1), None, Some(3)))),
      run(Query(transformed).result.head))
  }

  @Test def typedListAndTopLevelNulls(): Unit = {
    val numbers: JsonTransformCodec[Vector[Option[Int]]] =
      JsonTransformCodec.list[Vector[Option[Int]]](Json("[\"INTEGER\"]")) { array =>
        JsonTransformCodec.listElements(array)(_.asInstanceOf[Int])
      }
    val list =
      Json("[1,null,3]").bind.jsonTransform(numbers)
    val typed: Rep[Option[Vector[Option[Int]]]] = list
    val jsonNull =
      Json.nullValue.bind.jsonTransform(numbers)
    val sqlNull =
      Option.empty[Json].bind.jsonTransform(numbers)
    assertEquals(Some(Vector(Some(1), None, Some(3))), run(Query(list).result.head))
    assertEquals(None, run(Query(jsonNull).result.head))
    assertEquals(None, run(Query(sqlNull).result.head))
  }

  @Test def laxAndStrictConversions(): Unit = {
    val bad = Json("{\"age\":\"oops\"}").bind
    val missing = Json("{}").bind
    assertEquals(Some(Person(None)), run(Query(bad.jsonTransform(person)).result.head))
    assertEquals(Some(Person(None)), run(Query(missing.jsonTransform(person)).result.head))
    assertTrue(Try(run(Query(bad.jsonTransformStrict(person)).result.head)).isFailure)
    assertTrue(Try(run(Query(missing.jsonTransformStrict(person)).result.head)).isFailure)
    assertEquals(None, run(Query(Json.nullValue.bind.jsonTransformStrict(person)).result.head))
  }

  @Test def rejectsWrongStructureAndReportsDecoderMismatch(): Unit = {
    assertTrue(Try(JsonTransformCodec.struct[Person](Json("[\"INTEGER\"]"))(_ => Person(None))).isFailure)
    assertTrue(Try(JsonTransformCodec.list[Vector[Int]](Json("{\"x\":\"INTEGER\"}"))(_ => Vector.empty)).isFailure)
    assertTrue(Try(JsonTransformCodec.struct[Person](Json("{not-json}"))(_ => Person(None))).isFailure)
    assertTrue(Try(JsonTransformCodec.list[Vector[Int]](Json("[\"INTEGER\",]"))(_ => Vector.empty)).isFailure)

    val malformed = JsonTransformCodec.struct[Person](Json("{\"age\":\"NOT_A_TYPE\"}"))(_ => Person(None))
    assertTrue(Try(run(Query(Json("{\"age\":1}").bind.jsonTransform(malformed)).result.head)).isFailure)

    val mismatch = JsonTransformCodec.struct[Person](Json("{\"age\":\"INTEGER\"}")) { row =>
      Person(Some(row.getAttributes()(0).asInstanceOf[String].toInt))
    }
    val failure = Try(run(Query(Json("{\"age\":1}").bind.jsonTransform(mismatch)).result.head)).failed.get
    assertTrue(failure.toString, failure.toString.contains("Cannot decode json_transform result"))
  }

  @Test def sqlAliasesMatchCanonicalFunctions(): Unit = {
    val document = "{\"a\":1}"
    val structure = "{\"a\":\"INTEGER\"}"
    assertEquals(true, run(sql"select json_transform($document, $structure) = from_json($document, $structure)".as[Boolean].head))
    assertEquals(true, run(sql"select json_transform_strict($document, $structure) = from_json_strict($document, $structure)".as[Boolean].head))
  }
}
