package duckdbslick.api

import duckdbslick.types.{Json, JsonScalarPath}
import slick.ast.Library.SqlFunction
import slick.ast.TypedType
import slick.jdbc.JdbcProfile
import slick.lifted.{ExtensionMethods, OptionMapperDSL}

import scala.language.implicitConversions

/** Typed expressions for DuckDB's JSON scalar functions. */
trait DuckDBJsonScalarApi extends DuckDBJsonApi {
  this: JdbcProfile#JdbcAPI =>

  private val validFunction = new SqlFunction("json_valid")
  private val parseFunction = new SqlFunction("json")

  /** Validate raw SQL text, returning false for malformed JSON. */
  def jsonValid[P, R](raw: Rep[P])(implicit
      om: OptionMapperDSL.arg[String, P]#to[Boolean, R]
  ): Rep[R] = om.column(validFunction, raw.toNode)

  /** Parse and minify raw SQL text as JSON. Malformed input raises a SQL error. */
  def jsonParse[P, R](raw: Rep[P])(implicit
      om: OptionMapperDSL.arg[String, P]#to[Json, R]
  ): Rep[R] = parse(raw, om.liftedType(jsonJdbcType))

  /** Parse required text using the explicitly selected JSON representation. */
  def jsonParseAs[J](raw: Rep[String])(implicit representation: JsonRepresentation[J]): Rep[J] =
    parse(raw, representation.jdbcType)

  /** Parse nullable text, preserving SQL NULL in the selected representation. */
  def jsonParseAs[J](raw: Rep[Option[String]])(implicit
      representation: JsonRepresentation[J],
      overload: DummyImplicit
  ): Rep[Option[J]] = parse(raw, representation.jdbcType.optionType)

  private def parse[R](raw: Rep[?], mapping: TypedType[R]): Rep[R] =
    Rep.forNode[R](parseFunction.typed(mapping, raw.toNode))(mapping)
}

private[duckdbslick] trait DuckDBJsonScalarColumnApi {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonJdbcType: BaseColumnType[Json]
  implicit def jsonUbigintJdbcType: BaseColumnType[BigInt]
  implicit def jsonKeysJdbcType: BaseColumnType[Seq[String]]

  private val arrayLengthFunction = new SqlFunction("json_array_length")
  private val containsFunction = new SqlFunction("json_contains")
  private val keysFunction = new SqlFunction("json_keys")
  private val prettyFunction = new SqlFunction("json_pretty")
  private val structureFunction = new SqlFunction("json_structure")
  private val typeFunction = new SqlFunction("json_type")

  implicit def jsonScalarColumnExtensionMethods(c: Rep[Json]): JsonScalarColumnExtensionMethods[Json] =
    new JsonScalarColumnExtensionMethods(c)

  implicit def jsonScalarOptionColumnExtensionMethods(c: Rep[Option[Json]]): JsonScalarColumnExtensionMethods[Option[Json]] =
    new JsonScalarColumnExtensionMethods(c)

  class JsonScalarColumnExtensionMethods[P1](val c: Rep[P1])
      extends JsonScalarColumnMethods[Json, P1] {
    protected implicit def b1Type: TypedType[Json] = jsonJdbcType
  }

  private[duckdbslick] trait JsonScalarColumnMethods[J, P1] extends ExtensionMethods[J, P1] {
    private def atPath[T](function: SqlFunction, path: JsonScalarPath)(implicit
        mapping: BaseColumnType[T]
    ): Rep[Option[T]] = {
      val resultType = mapping.optionType
      Rep.forNode[Option[T]](
        function.typed(resultType, n, LiteralColumn(path.value).bind.toNode)
      )(resultType)
    }

    def jsonArrayLength[R](implicit om: o#to[BigInt, R]): Rep[R] =
      om.column(arrayLengthFunction, n)(jsonUbigintJdbcType)

    def jsonArrayLength(path: JsonScalarPath): Rep[Option[BigInt]] =
      atPath[BigInt](arrayLengthFunction, path)(jsonUbigintJdbcType)

    def jsonContains[P2, R](needle: Rep[P2])(implicit
        om: o#arg[J, P2]#to[Boolean, R]
    ): Rep[R] = om.column(containsFunction, n, needle.toNode)

    def jsonKeys[R](implicit om: o#to[Seq[String], R]): Rep[R] =
      om.column(keysFunction, n)(jsonKeysJdbcType)

    def jsonKeys(path: JsonScalarPath): Rep[Option[Seq[String]]] =
      atPath[Seq[String]](keysFunction, path)(jsonKeysJdbcType)

    def jsonPretty[R](implicit om: o#to[String, R]): Rep[R] =
      om.column(prettyFunction, n)

    def jsonStructure[R](implicit om: o#to[J, R]): Rep[R] =
      om.column(structureFunction, n)

    def jsonType[R](implicit om: o#to[String, R]): Rep[R] =
      om.column(typeFunction, n)

    def jsonType(path: JsonScalarPath): Rep[Option[String]] =
      atPath[String](typeFunction, path)
  }
}
