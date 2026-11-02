package components

import duckdbslick.types.Json
import slick.ast.{BaseTypedType, Node, OptionType, Type}
import slick.ast.Library.{Cast, SqlFunction}
import slick.jdbc.JdbcProfile
import slick.jdbc.JdbcType

/** Builders for DuckDB functions that create SQL JSON values. */
trait DuckDBJsonFunctionsComponent {
  self: JdbcProfile with DuckDBJdbcTypesComponent =>

  trait JsonApi extends JdbcAPI {
    implicit def jsonJdbcType: BaseColumnType[Json]

    /** A SQL JSON mapping for an opt-in Scala representation.
      *
      * Implementations must preserve JSON null as a present value. SQL NULL is
      * represented by `Option[J]` and never passed to `decode`.
      */
    trait JsonRepresentation[J] {
      def jdbcType: BaseColumnType[J]
      def encode(value: J): String
      def decode(text: String): J
    }

    implicit val rawJsonRepresentation: JsonRepresentation[Json] =
      new JsonRepresentation[Json] {
        override def jdbcType: BaseColumnType[Json] = jsonJdbcType
        override def encode(value: Json): String = value.value
        override def decode(text: String): Json = Json(text)
      }

    private val toJsonFunction = new SqlFunction("to_json")
    private val jsonArrayFunction = new SqlFunction("json_array")
    private val jsonObjectFunction = new SqlFunction("json_object")
    private val jsonMergePatchFunction = new SqlFunction("json_merge_patch")

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
        implicit representation: JsonRepresentation[J]
    ): Rep[J] = {
      val jdbcType = representation.jdbcType
      Rep.forNode[J](function.typed(jdbcType, args: _*))(jdbcType)
    }

    private def optionalJsonResult[J](function: SqlFunction, args: Seq[Node])(
        implicit representation: JsonRepresentation[J]
    ): Rep[Option[J]] = {
      val jdbcType = representation.jdbcType.optionType
      Rep.forNode[Option[J]](function.typed(jdbcType, args: _*))(jdbcType)
    }

    /** Convert a non-null SQL value to JSON. Strings become JSON strings. */
    def toJson[T: BaseTypedType](value: Rep[T]): Rep[Json] =
      toJsonAs[Json, T](value)

    /** Convert a nullable SQL value; SQL NULL remains SQL NULL. */
    def toJsonOption[T](value: Rep[Option[T]]): Rep[Option[Json]] =
      toJsonOptionAs[Json, T](value)(rawJsonRepresentation)

    def toJsonAs[J, T: BaseTypedType](value: Rep[T])(
        implicit representation: JsonRepresentation[J]
    ): Rep[J] = jsonResult[J](toJsonFunction, Seq(argument(value)))

    def toJsonOptionAs[J, T](value: Rep[Option[T]])(
        implicit representation: JsonRepresentation[J]
    ): Rep[Option[J]] = optionalJsonResult[J](toJsonFunction, Seq(argument(value)))

    /** SQL NULL arguments become JSON null elements. */
    def jsonArray(values: Rep[?]*): Rep[Json] =
      jsonArrayAs[Json](values: _*)(rawJsonRepresentation)

    def jsonArrayAs[J](values: Rep[?]*)(
        implicit representation: JsonRepresentation[J]
    ): Rep[J] = jsonResult[J](jsonArrayFunction, values.map(argument))

    /** Keys are non-null SQL strings; SQL NULL values become JSON null. */
    def jsonObject(fields: (Rep[String], Rep[?])*): Rep[Json] =
      jsonObjectAs[Json](fields: _*)(rawJsonRepresentation)

    def jsonObjectAs[J](fields: (Rep[String], Rep[?])*)(
        implicit representation: JsonRepresentation[J]
    ): Rep[J] = jsonResult[J](
      jsonObjectFunction,
      fields.flatMap { case (key, value) => Seq(key.toNode, argument(value)) }
    )

    private def mergePatchArguments(document: Rep[?], patch: Rep[?]): Seq[Node] =
      Seq(argument(document), argument(patch))

    /** A SQL NULL document is ignored by DuckDB; a SQL NULL patch is not. */
    def jsonMergePatch[J](document: Rep[J], patch: Rep[J])(
        implicit representation: JsonRepresentation[J]
    ): Rep[J] = jsonResult[J](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )

    def jsonMergePatchNullableDocument[J](document: Rep[Option[J]], patch: Rep[J])(
        implicit representation: JsonRepresentation[J]
    ): Rep[J] = jsonResult[J](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )

    def jsonMergePatchNullablePatch[J](document: Rep[J], patch: Rep[Option[J]])(
        implicit representation: JsonRepresentation[J]
    ): Rep[Option[J]] = optionalJsonResult[J](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )

    def jsonMergePatchNullable[J](document: Rep[Option[J]], patch: Rep[Option[J]])(
        implicit representation: JsonRepresentation[J]
    ): Rep[Option[J]] = optionalJsonResult[J](
      jsonMergePatchFunction,
      mergePatchArguments(document, patch)
    )
  }
}
