package duckdbslick.api

import duckdbslick.types.{Json, JsonPath, JsonTransformCodec}
import slick.ast.{BaseTypedType, Node, OptionType, Type, TypedType}
import slick.ast.Library.{Cast, SqlFunction}
import slick.jdbc.{JdbcProfile, JdbcType}
import slick.lifted.{ExtensionMethods, OptionMapperDSL}

import scala.annotation.tailrec
import scala.language.implicitConversions

/** Combined DuckDB JSON creation, extraction, merge-patch, and column mutation API. */
trait DuckDBJsonApi extends DuckDBJsonExtractionApi {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonPathJdbcType: BaseColumnType[JsonPath]

  protected def jsonTransformJdbcType[A](codec: JsonTransformCodec[A]): BaseColumnType[A]

  protected def requireJsonMutations(): Unit

  // JSON creation

  private val toJsonFunction = new SqlFunction("to_json")
  private val jsonArrayFunction = new SqlFunction("json_array")
  private val jsonObjectFunction = new SqlFunction("json_object")

  /** Convert a non-null SQL value to JSON. Strings become JSON strings. */
  def toJson[T: BaseTypedType](value: Rep[T]): Rep[Json] =
    jsonResult[Json](toJsonFunction, Seq(argument(value)))(jsonJdbcType)

  /** Convert a nullable SQL value; SQL NULL remains SQL NULL. */
  def toJson[T](value: Rep[Option[T]]): Rep[Option[Json]] =
    optionalJsonResult[Json](toJsonFunction, Seq(argument(value)))(jsonJdbcType)

  /** SQL NULL arguments become JSON null elements. */
  def jsonArray(values: Rep[?]*): Rep[Json] =
    jsonResult[Json](jsonArrayFunction, values.map(argument))(jsonJdbcType)

  /** Keys are non-null SQL strings; SQL NULL values become JSON null. */
  def jsonObject(fields: (Rep[String], Rep[?])*): Rep[Json] =
    jsonResult[Json](jsonObjectFunction,
      fields.flatMap { case (key, value) => Seq(key.toNode, argument(value)) }
    )(jsonJdbcType)

  // JSON merge-patch

  private val jsonMergePatchFunction = new SqlFunction("json_merge_patch")

  /** A SQL NULL document is ignored; a SQL NULL patch makes the result SQL NULL.
    * The result has the same nullability as the patch.
    */
  def jsonMergePatch[D, P](document: Rep[D], patch: Rep[P])(implicit
      documentMapping: OptionMapperDSL.arg[Json, D]#to[Json, D],
      patchMapping: OptionMapperDSL.arg[Json, P]#to[Json, P]
  ): Rep[P] = {
    // Both mappers restrict inputs to Json or Option[Json]. Only the patch
    // mapper determines the result type because NULL documents are ignored.
    patchMapping.column(jsonMergePatchFunction, argument(document), argument(patch))(jsonJdbcType)
  }

  // JSON column mutations (DuckDB 2.0+)

  private val jsonSetFunction = new SqlFunction("json_set")
  private val jsonInsertFunction = new SqlFunction("json_insert")
  private val jsonReplaceFunction = new SqlFunction("json_replace")
  private val jsonRemoveFunction = new SqlFunction("json_remove")

  implicit def jsonColumnExtensionMethods(
      c: Rep[Json]
  ): JsonColumnExtensionMethods[Json] = new JsonColumnExtensionMethods(c)

  implicit def jsonOptionColumnExtensionMethods(
      c: Rep[Option[Json]]
  ): JsonColumnExtensionMethods[Option[Json]] = new JsonColumnExtensionMethods(c)

  /** Opt-in transform methods for another Scala representation of SQL JSON. */
  implicit def mappedJsonTransformColumnMethods[J](
      c: Rep[J]
  )(implicit input: JsonTransformInput[J]): MappedJsonTransformColumnMethods[J] =
    new MappedJsonTransformColumnMethods(c)

  implicit def mappedJsonTransformOptionColumnMethods[J](
      c: Rep[Option[J]]
  )(implicit input: JsonTransformInput[J]): MappedJsonTransformColumnMethods[Option[J]] =
    new MappedJsonTransformColumnMethods(c)

  class MappedJsonTransformColumnMethods[J](val c: Rep[J]) {
    def jsonTransform[A](codec: JsonTransformCodec[A]): JsonTransformRep[A] =
      transformJson(new SqlFunction("json_transform"), c.toNode, codec)

    def jsonTransformStrict[A](codec: JsonTransformCodec[A]): JsonTransformRep[A] =
      transformJson(new SqlFunction("json_transform_strict"), c.toNode, codec)
  }

  class JsonColumnExtensionMethods[P1](val c: Rep[P1])
      extends ExtensionMethods[Json, P1] {
    protected implicit def b1Type: TypedType[Json] = jsonJdbcType

    /** Decode JSON into the codec's fixed SQL STRUCT or LIST type. */
    def jsonTransform[A](codec: JsonTransformCodec[A]): JsonTransformRep[A] =
      transformJson(new SqlFunction("json_transform"), n, codec)

    /** As above, but fail on invalid field casts and missing fields. */
    def jsonTransformStrict[A](codec: JsonTransformCodec[A]): JsonTransformRep[A] =
      transformJson(new SqlFunction("json_transform_strict"), n, codec)

    def jsonSet[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[Json, P3]#to[Json, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonSetFunction, n, path.toNode, value.toNode)
    }

    def jsonInsert[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[Json, P3]#to[Json, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonInsertFunction, n, path.toNode, value.toNode)
    }

    def jsonReplace[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[Json, P3]#to[Json, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonReplaceFunction, n, path.toNode, value.toNode)
    }

    def jsonRemove[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonPath, P2]#to[Json, R]
    ): Rep[Option[Json]] = {
      requireJsonMutations()
      // Removing the root path returns SQL NULL even for non-null inputs.
      Rep.forNode[Option[Json]](
        jsonRemoveFunction.typed(jsonJdbcType.optionType, n, path.toNode)
      )(jsonJdbcType.optionType)
    }
  }

  private def transformJson[A](function: SqlFunction, document: Node,
      codec: JsonTransformCodec[A]): JsonTransformRep[A] = {
    val resultType = jsonTransformJdbcType(codec).optionType
    new JsonTransformRep[A](
      function.typed(resultType, document, LiteralColumn(codec.structure).toNode),
      resultType
    )
  }

  // Internal expression helpers

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
}
