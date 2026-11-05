package duckdbslick.components

import duckdbslick.types.Json
import slick.ast.{BaseTypedType, Node, OptionType, Type}
import slick.ast.Library.{Cast, SqlFunction}
import slick.jdbc.JdbcProfile
import slick.jdbc.JdbcType

import scala.annotation.tailrec

/** Builders for DuckDB functions that create SQL JSON values. */
trait DuckDBJsonFunctionsComponent {
  self: JdbcProfile & DuckDBJdbcTypesComponent =>

  trait JsonApi {
    this: JdbcAPI =>
    implicit def jsonJdbcType: BaseColumnType[Json]

    private val toJsonFunction = new SqlFunction("to_json")
    private val jsonArrayFunction = new SqlFunction("json_array")
    private val jsonObjectFunction = new SqlFunction("json_object")
    private val jsonMergePatchFunction = new SqlFunction("json_merge_patch")

    @tailrec
    private def isJsonType(tpe: Type): Boolean = tpe match {
      case optional: OptionType => isJsonType(optional.elementType)
      case jdbc: JdbcType[?] => jdbc.sqlTypeName(None).equalsIgnoreCase("JSON")
      case _ => false
    }

    // DuckDB's polymorphic functions otherwise see setString placeholders as
    // VARCHAR and quote JSON input a second time.
    private def argument(value: Rep[?]): Node = {
      val node = value.toNode
      val tpe = value.asInstanceOf[Rep.TypedRep[?]].tpe
      if (isJsonType(tpe))
        Cast.typed(tpe, node, LiteralColumn("JSON").toNode)
      else node
    }

    private def jsonResult[J](function: SqlFunction, args: Seq[Node])(
        jdbcType: BaseColumnType[J]
    ): Rep[J] = {
      Rep.forNode[J](function.typed(jdbcType, args *))(jdbcType)
    }

    private def optionalJsonResult[J](function: SqlFunction, args: Seq[Node])(
        mapping: BaseColumnType[J]
    ): Rep[Option[J]] = {
      val jdbcType = mapping.optionType
      Rep.forNode[Option[J]](function.typed(jdbcType, args *))(jdbcType)
    }

    /** Convert a non-null SQL value to JSON. Strings become JSON strings. */
    def toJson[T: BaseTypedType](value: Rep[T]): Rep[Json] =
      jsonResult[Json](toJsonFunction, Seq(argument(value)))(jsonJdbcType)

    /** Convert a nullable SQL value; SQL NULL remains SQL NULL. */
    def toJsonOption[T](value: Rep[Option[T]]): Rep[Option[Json]] =
      optionalJsonResult[Json](toJsonFunction, Seq(argument(value)))(jsonJdbcType)

    /** SQL NULL arguments become JSON null elements. */
    def jsonArray(values: Rep[?]*): Rep[Json] =
      jsonResult[Json](jsonArrayFunction, values.map(argument))(jsonJdbcType)

    /** Keys are non-null SQL strings; SQL NULL values become JSON null. */
    def jsonObject(fields: (Rep[String], Rep[?])*): Rep[Json] =
      jsonResult[Json](jsonObjectFunction,
        fields.flatMap { case (key, value) => Seq(key.toNode, argument(value)) }
      )(jsonJdbcType)

    private def mergePatchArguments(document: Rep[?], patch: Rep[?]): Seq[Node] =
      Seq(argument(document), argument(patch))

    /** A SQL NULL document is ignored by DuckDB; a SQL NULL patch is not. */
    def jsonMergePatch(document: Rep[Json], patch: Rep[Json]): Rep[Json] = jsonResult[Json](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )(jsonJdbcType)

    def jsonMergePatchNullableDocument(document: Rep[Option[Json]], patch: Rep[Json]): Rep[Json] = jsonResult[Json](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )(jsonJdbcType)

    def jsonMergePatchNullablePatch(document: Rep[Json], patch: Rep[Option[Json]]): Rep[Option[Json]] = optionalJsonResult[Json](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )(jsonJdbcType)

    def jsonMergePatchNullable(document: Rep[Option[Json]], patch: Rep[Option[Json]]): Rep[Option[Json]] = optionalJsonResult[Json](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )(jsonJdbcType)
  }
}
