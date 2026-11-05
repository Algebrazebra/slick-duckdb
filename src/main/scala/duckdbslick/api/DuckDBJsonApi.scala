package duckdbslick.api

import duckdbslick.types.{Json, JsonPath}
import slick.ast.{BaseTypedType, Node, OptionType, Type, TypedType}
import slick.ast.Library.{Cast, SqlFunction}
import slick.jdbc.{JdbcProfile, JdbcType}
import slick.lifted.ExtensionMethods

import scala.annotation.tailrec
import scala.language.implicitConversions

/** DuckDB JSON creation, merge-patch, and column mutation functions. */
trait DuckDBJsonApi {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonJdbcType: BaseColumnType[Json]
  implicit def jsonPathJdbcType: BaseColumnType[JsonPath]

  protected def requireJsonMutations(): Unit

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
}
