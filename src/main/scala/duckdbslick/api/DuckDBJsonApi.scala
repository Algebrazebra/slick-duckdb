package duckdbslick.api

import duckdbslick.types.{JsonPath, JsonTransformCodec}
import slick.ast.{BaseTypedType, Node, TypedType}
import slick.ast.Library.SqlFunction
import slick.jdbc.JdbcProfile
import slick.lifted.{ExtensionMethods, OptionMapperDSL}

import scala.language.implicitConversions

// DuckDB JSON creation, extraction, aggregation, patching, and column mutations.
trait DuckDBJsonApi[J] extends DuckDBJsonExtractionApi[J] with DuckDBJsonAggregateApi[J] {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonPathJdbcType: BaseColumnType[JsonPath]

  protected def jsonTransformJdbcType[A](codec: JsonTransformCodec[A]): BaseColumnType[A]

  protected def requireJsonMutations(): Unit

  protected def requireJsonPatching(): Unit

  /** Limits variadic deep-merge arguments to JSON expressions while allowing
    * required and nullable patches in the same call.
    */
  final class JsonDeepMergePatch private (val node: Node)

  object JsonDeepMergePatch {
    implicit def fromJson(value: Rep[J]): JsonDeepMergePatch =
      new JsonDeepMergePatch(argument(value))

    implicit def fromOptionalJson(value: Rep[Option[J]]): JsonDeepMergePatch =
      new JsonDeepMergePatch(argument(value))
  }

  // JSON creation

  private val toJsonFunction = new SqlFunction("to_json")
  private val jsonArrayFunction = new SqlFunction("json_array")
  private val jsonObjectFunction = new SqlFunction("json_object")

  /** Convert a non-null SQL value to JSON. Strings become JSON strings. */
  def toJson[T: BaseTypedType](value: Rep[T]): Rep[J] =
    jsonResult[J](toJsonFunction, Seq(argument(value)))(jsonJdbcType)

  /** Convert a nullable SQL value; SQL NULL remains SQL NULL. */
  def toJson[T](value: Rep[Option[T]]): Rep[Option[J]] =
    optionalJsonResult[J](toJsonFunction, Seq(argument(value)))(jsonJdbcType)

  /** SQL NULL arguments become JSON null elements. */
  def jsonArray(values: Rep[?]*): Rep[J] =
    jsonResult[J](jsonArrayFunction, values.map(argument))(jsonJdbcType)

  /** Keys are non-null SQL strings; SQL NULL values become JSON null. */
  def jsonObject(fields: (Rep[String], Rep[?])*): Rep[J] =
    jsonResult[J](jsonObjectFunction,
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
      documentMapping: OptionMapperDSL.arg[J, D]#to[J, D],
      patchMapping: OptionMapperDSL.arg[J, P]#to[J, P]
  ): Rep[P] = {
    // Both mappers restrict inputs to J or Option[J]. Only the patch
    // mapper determines the result type because NULL documents are ignored.
    patchMapping.column(jsonMergePatchFunction, argument(document), argument(patch))(jsonJdbcType)
  }

  // JSON column mutations (DuckDB 2.0+)

  private val jsonSetFunction = new SqlFunction("json_set")
  private val jsonInsertFunction = new SqlFunction("json_insert")
  private val jsonReplaceFunction = new SqlFunction("json_replace")
  private val jsonRemoveFunction = new SqlFunction("json_remove")

  implicit def jsonColumnExtensionMethods(
      c: Rep[J]
  ): JsonColumnExtensionMethods[J] = new JsonColumnExtensionMethods(c)

  implicit def jsonOptionColumnExtensionMethods(
      c: Rep[Option[J]]
  ): JsonColumnExtensionMethods[Option[J]] = new JsonColumnExtensionMethods(c)

  class JsonColumnExtensionMethods[P1](val c: Rep[P1])
      extends ExtensionMethods[J, P1] {
    protected implicit def b1Type: TypedType[J] = jsonJdbcType

    /** Decode this JSON column using the fixed STRUCT or LIST result codec. */
    def jsonTransform[A](codec: JsonTransformCodec[A]): JsonTransformRep[A] =
      transformJson(jsonTransformFunction, n, codec)

    /** Fail on invalid casts or missing fields during transformation. */
    def jsonTransformStrict[A](codec: JsonTransformCodec[A]): JsonTransformRep[A] =
      transformJson(jsonTransformStrictFunction, n, codec)

    /** Applies required patches in order and returns required JSON. */
    def jsonDeepMerge(patch: Rep[J], furtherPatches: Rep[J]*): Rep[J] = {
      requireJsonPatching()
      jsonResult[J](
        jsonDeepMergeFunction,
        Seq(argument(c), argument(patch)) ++ furtherPatches.map(argument)
      )(jsonJdbcType)
    }

    /** Preserves typed varargs calls with a sequence of nullable patches.
      * DummyImplicit gives this overload a distinct erased signature.
      */
    def jsonDeepMerge(
        patch: Rep[Option[J]],
        furtherPatches: Rep[Option[J]]*
    )(implicit overload: DummyImplicit): Rep[Option[J]] =
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
    ): Rep[Option[J]] = {
      requireJsonPatching()
      optionalJsonResult[J](
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
      * JSON-null object fields in the patch indicate deletions.
      */
    def jsonMergePatchDiff[P2](modified: Rep[P2])(implicit
        modifiedMapping: OptionMapperDSL.arg[J, P2]#to[J, P2]
    ): Rep[P2] = {
      requireJsonPatching()
      modifiedMapping.column(
        jsonMergePatchDiffFunction,
        argument(c),
        argument(modified)
      )(jsonJdbcType)
    }

    /** Removes JSON-null members of objects, retaining null array elements. */
    def jsonStripNulls[R](implicit om: o#to[J, R]): Rep[R] = {
      requireJsonPatching()
      om.column(jsonStripNullsFunction, argument(c))
    }

    def jsonSet[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[J, P3]#to[J, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonSetFunction, n, path.toNode, value.toNode)
    }

    def jsonInsert[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[J, P3]#to[J, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonInsertFunction, n, path.toNode, value.toNode)
    }

    def jsonReplace[P2, P3, R](path: Rep[P2], value: Rep[P3])(implicit
        om: o#arg[JsonPath, P2]#arg[J, P3]#to[J, R]
    ): Rep[R] = {
      requireJsonMutations()
      om.column(jsonReplaceFunction, n, path.toNode, value.toNode)
    }

    def jsonRemove[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonPath, P2]#to[J, R]
    ): Rep[Option[J]] = {
      requireJsonMutations()
      // Removing the root path returns SQL NULL even for non-null inputs.
      Rep.forNode[Option[J]](
        jsonRemoveFunction.typed(jsonJdbcType.optionType, n, path.toNode)
      )(jsonJdbcType.optionType)
    }
  }

  private val jsonTransformFunction = new SqlFunction("json_transform")
  private val jsonTransformStrictFunction = new SqlFunction("json_transform_strict")

  private def transformJson[A](function: SqlFunction, document: Node,
      codec: JsonTransformCodec[A]): JsonTransformRep[A] = {
    val resultType = jsonTransformJdbcType(codec).optionType
    new JsonTransformRep[A](
      function.typed(resultType, document, LiteralColumn(codec.structure.value).bind.toNode),
      resultType
    )
  }

  // Internal expression helpers

  // DuckDB's polymorphic functions otherwise see setString placeholders as
  // VARCHAR and quote JSON input a second time.
  private def argument(value: Rep[?]): Node = {
    val tpe = value.asInstanceOf[Rep.TypedRep[?]].tpe
    jsonOperand(value.toNode, tpe)
  }

  private def jsonResult[T](function: SqlFunction, args: Seq[Node])(
      jdbcType: BaseColumnType[T]
  ): Rep[T] = {
    Rep.forNode[T](function.typed(jdbcType, args *))(jdbcType)
  }

  private def optionalJsonResult[T](function: SqlFunction, args: Seq[Node])(
      mapping: BaseColumnType[T]
  ): Rep[Option[T]] = {
    val jdbcType = mapping.optionType
    Rep.forNode[Option[T]](function.typed(jdbcType, args *))(jdbcType)
  }
}
