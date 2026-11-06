package duckdbslick.api

import duckdbslick.types.{Json, JsonPath}
import slick.ast.{BaseTypedType, Node, OptionType, Type, TypedType}
import slick.ast.Library.{Cast, SqlFunction}
import slick.jdbc.{JdbcProfile, JdbcType}
import slick.lifted.{ExtensionMethods, OptionMapperDSL}

import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.reflect.ClassTag

/** Combined DuckDB JSON creation, extraction, patching, and column mutation API. */
trait DuckDBJsonApi extends DuckDBJsonExtractionApi {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonPathJdbcType: BaseColumnType[JsonPath]

  protected def requireJsonMutations(): Unit

  protected def requireJsonPatching(): Unit

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

  private val jsonDeepMergeFunction = new SqlFunction("json_deep_merge")
  private val jsonMergePatchDiffFunction = new SqlFunction("json_merge_patch_diff")
  private val jsonNormalizeFunction = new SqlFunction("json_normalize")
  private val jsonStripNullsFunction = new SqlFunction("json_strip_nulls")

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

  /** Internal opt-in mapping for another Scala representation of SQL JSON.
    * The codec creates a JDBC mapping over the existing raw JSON mapping, so
    * JSON null remains a present value and SQL NULL stays an Option.empty.
    */
  private[duckdbslick] final class JsonRepresentation[J](
      val jdbcType: BaseColumnType[J],
      val encode: J => Json,
      val decode: Json => J
  ) {
    require(jdbcType.sqlTypeName(None).equalsIgnoreCase("JSON"),
      "JSON representation mapping must use SQL JSON")

    def column(c: Rep[J]): JsonRepresentationColumnExtensionMethods[J, J] =
      new JsonRepresentationColumnExtensionMethods[J, J](c, this)

    def optionalColumn(c: Rep[Option[J]]): JsonRepresentationColumnExtensionMethods[J, Option[J]] =
      new JsonRepresentationColumnExtensionMethods[J, Option[J]](c, this)
  }

  private[duckdbslick] def jsonRepresentation[J: ClassTag](
      encode: J => Json,
      decode: Json => J
  ): JsonRepresentation[J] = {
    val mapping = MappedColumnType.base[J, Json](encode, decode)
    new JsonRepresentation(mapping, encode, decode)
  }

  private lazy val rawJsonRepresentation =
    new JsonRepresentation[Json](jsonJdbcType, identity[Json], identity[Json])

  implicit def jsonColumnExtensionMethods(
      c: Rep[Json]
  ): JsonColumnExtensionMethods[Json] = new JsonColumnExtensionMethods(c)

  implicit def jsonOptionColumnExtensionMethods(
      c: Rep[Option[Json]]
  ): JsonColumnExtensionMethods[Option[Json]] = new JsonColumnExtensionMethods(c)

  class JsonColumnExtensionMethods[P1](c: Rep[P1])
      extends JsonRepresentationColumnExtensionMethods[Json, P1](c, rawJsonRepresentation) {

    /** Applies one or more patches in order. SQL NULL documents are ignored;
      * a SQL NULL patch makes the result SQL NULL.
      */
    def jsonDeepMerge[P2](patch: Rep[P2], furtherPatches: Rep[P2]*)(implicit
        patchMapping: OptionMapperDSL.arg[Json, P2]#to[Json, P2]
    ): Rep[P2] = {
      requireJsonPatching()
      patchMapping.column(
        jsonDeepMergeFunction,
        (Seq(argument(c), argument(patch)) ++ furtherPatches.map(argument)) *
      )(jsonJdbcType)
    }

    /** Returns the RFC 7396 patch from this document to `modified`.
      * A SQL NULL original is ignored; nullability follows `modified`.
      */
    def jsonMergePatchDiff[P2](modified: Rep[P2])(implicit
        modifiedMapping: OptionMapperDSL.arg[Json, P2]#to[Json, P2]
    ): Rep[P2] = {
      requireJsonPatching()
      modifiedMapping.column(
        jsonMergePatchDiffFunction,
        argument(c),
        argument(modified)
      )(jsonJdbcType)
    }
  }

  /** Shared JSON builders for raw Json and opt-in SQL JSON mappings. */
  private[duckdbslick] class JsonRepresentationColumnExtensionMethods[J, P1](
      val c: Rep[P1],
      representation: JsonRepresentation[J]
  ) extends ExtensionMethods[J, P1] {
    protected implicit def b1Type: TypedType[J] = representation.jdbcType

    /** Canonical JSON text. DuckDB declares the result as VARCHAR. */
    def jsonNormalize[R](implicit om: o#to[String, R]): Rep[R] = {
      requireJsonPatching()
      om.column(jsonNormalizeFunction, argument(c))
    }

    /** Removes JSON-null members of objects, retaining null array elements. */
    def jsonStripNulls[R](implicit om: o#to[J, R]): Rep[R] = {
      requireJsonPatching()
      om.column(jsonStripNullsFunction, argument(c))(representation.jdbcType)
    }

    def jsonSet[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[J, P3]#to[J, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonSetFunction, n, path.toNode, value.toNode)(representation.jdbcType)
    }

    def jsonInsert[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[J, P3]#to[J, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonInsertFunction, n, path.toNode, value.toNode)(representation.jdbcType)
    }

    def jsonReplace[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[J, P3]#to[J, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonReplaceFunction, n, path.toNode, value.toNode)(representation.jdbcType)
    }

    def jsonRemove[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonPath, P2]#to[J, R]
    ): Rep[Option[J]] = {
      requireJsonMutations()
      // Removing the root path returns SQL NULL even for non-null inputs.
      val jdbcType = representation.jdbcType.optionType
      Rep.forNode[Option[J]](
        jsonRemoveFunction.typed(jdbcType, n, path.toNode)
      )(jdbcType)
    }
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
