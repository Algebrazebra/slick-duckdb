package duckdbslick.api

import java.util.Objects.requireNonNull

import duckdbslick.types.{Json, JsonPath}
import slick.ast.{BaseTypedType, Node, TypedType}
import slick.ast.Library.SqlFunction
import slick.jdbc.JdbcProfile
import slick.lifted.{ExtensionMethods, OptionMapperDSL}

import scala.language.implicitConversions
import scala.reflect.ClassTag

// DuckDB JSON creation, extraction, aggregation, patching, and column mutations.
trait DuckDBJsonApi extends DuckDBJsonExtractionApi with DuckDBJsonAggregateApi {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonPathJdbcType: BaseColumnType[JsonPath]

  protected def requireJsonMutations(): Unit

  protected def requireJsonPatching(): Unit

  /** Limits variadic deep-merge arguments to JSON expressions while allowing
    * required and nullable patches in the same call.
    */
  final class JsonDeepMergePatch private (val node: Node)

  object JsonDeepMergePatch {
    implicit def fromJson(value: Rep[Json]): JsonDeepMergePatch =
      new JsonDeepMergePatch(argument(value))

    implicit def fromOptionalJson(value: Rep[Option[Json]]): JsonDeepMergePatch =
      new JsonDeepMergePatch(argument(value))
  }

  /** An opt-in SQL JSON mapping for a Scala type. JSON null is a present J;
    * Option[J] represents SQL NULL.
    */
  final class JsonRepresentation[J] private[DuckDBJsonApi] (
      val jdbcType: BaseColumnType[J]
  )

  /** Build a SQL JSON mapping from a codec. Decode JSON null as a value and
    * report malformed or unsupported input by throwing an exception.
    */
  def jsonRepresentation[J: ClassTag](
      encode: J => String,
      decode: String => J
  ): JsonRepresentation[J] = new JsonRepresentation(
    MappedColumnType.base[J, Json](
      value => Json(encode(value)),
      json => requireNonNull(decode(json.value), "JSON decoder returned null")
    )
  )

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

  /** Select an alternate JSON result for a nonnullable SQL input. */
  def toJsonAs[J, T: BaseTypedType](value: Rep[T])(implicit
      representation: JsonRepresentation[J]
  ): Rep[J] = jsonResult[J](toJsonFunction, Seq(argument(value)))(representation.jdbcType)

  /** Select an alternate JSON result for a nullable SQL input. */
  def toJsonAs[J, T](value: Rep[Option[T]])(implicit
      representation: JsonRepresentation[J]
  ): Rep[Option[J]] =
    optionalJsonResult[J](toJsonFunction, Seq(argument(value)))(representation.jdbcType)

  /** SQL NULL arguments become JSON null elements. */
  def jsonArray(values: Rep[?]*): Rep[Json] =
    jsonResult[Json](jsonArrayFunction, values.map(argument))(jsonJdbcType)

  def jsonArrayAs[J](values: Rep[?]*)(implicit representation: JsonRepresentation[J]): Rep[J] =
    jsonResult[J](jsonArrayFunction, values.map(argument))(representation.jdbcType)

  /** Keys are non-null SQL strings; SQL NULL values become JSON null. */
  def jsonObject(fields: (Rep[String], Rep[?])*): Rep[Json] =
    jsonResult[Json](jsonObjectFunction, jsonObjectArguments(fields))(jsonJdbcType)

  def jsonObjectAs[J](fields: (Rep[String], Rep[?])*)(implicit
      representation: JsonRepresentation[J]
  ): Rep[J] = jsonResult[J](jsonObjectFunction, jsonObjectArguments(fields))(representation.jdbcType)

  private def jsonObjectArguments(fields: Seq[(Rep[String], Rep[?])]): Seq[Node] =
    fields.flatMap { case (key, value) => Seq(key.toNode, argument(value)) }

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

  /** Preserve the representation and the patch's nullability. */
  def jsonMergePatchAs[J, D, P](document: Rep[D], patch: Rep[P])(implicit
      documentMapping: OptionMapperDSL.arg[J, D]#to[J, D],
      patchMapping: OptionMapperDSL.arg[J, P]#to[J, P],
      representation: JsonRepresentation[J]
  ): Rep[P] =
    patchMapping.column(jsonMergePatchFunction, argument(document), argument(patch))(
      representation.jdbcType
    )

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

  class JsonColumnExtensionMethods[P1](val c: Rep[P1])
      extends ExtensionMethods[Json, P1] {
    protected implicit def b1Type: TypedType[Json] = jsonJdbcType

    /** Applies required patches in order and returns required JSON. */
    def jsonDeepMerge(patch: Rep[Json], furtherPatches: Rep[Json]*): Rep[Json] = {
      requireJsonPatching()
      jsonResult[Json](
        jsonDeepMergeFunction,
        Seq(argument(c), argument(patch)) ++ furtherPatches.map(argument)
      )(jsonJdbcType)
    }

    /** Preserves typed varargs calls with a sequence of nullable patches.
      * DummyImplicit gives this overload a distinct erased signature.
      */
    def jsonDeepMerge(
        patch: Rep[Option[Json]],
        furtherPatches: Rep[Option[Json]]*
    )(implicit overload: DummyImplicit): Rep[Option[Json]] =
      jsonDeepMerge(
        JsonDeepMergePatch.fromOptionalJson(patch),
        furtherPatches.map(JsonDeepMergePatch.fromOptionalJson) *
      )

    /** Accepts required and nullable patches in any order. A SQL NULL patch
      * produces a NULL intermediate value; a later patch can yield JSON again.
      */
    def jsonDeepMerge(
        patch: JsonDeepMergePatch,
        furtherPatches: JsonDeepMergePatch*
    ): Rep[Option[Json]] = {
      requireJsonPatching()
      optionalJsonResult[Json](
        jsonDeepMergeFunction,
        Seq(argument(c), patch.node) ++ furtherPatches.map(_.node)
      )(jsonJdbcType)
    }

    /** Canonical JSON text. DuckDB declares the result as VARCHAR. */
    def jsonNormalize[R](implicit om: o#to[String, R]): Rep[R] = {
      requireJsonPatching()
      om.column(jsonNormalizeFunction, argument(c))
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

    /** Removes JSON-null members of objects, retaining null array elements. */
    def jsonStripNulls[R](implicit om: o#to[Json, R]): Rep[R] = {
      requireJsonPatching()
      om.column(jsonStripNullsFunction, argument(c))
    }

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

  // Internal expression helpers

  // DuckDB's polymorphic functions otherwise see setString placeholders as
  // VARCHAR and quote JSON input a second time.
  private def argument(value: Rep[?]): Node = {
    val tpe = value.asInstanceOf[Rep.TypedRep[?]].tpe
    jsonOperand(value.toNode, tpe)
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
