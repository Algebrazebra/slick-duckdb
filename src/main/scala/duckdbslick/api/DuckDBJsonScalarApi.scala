package duckdbslick.api

import duckdbslick.types.JsonScalarPath
import slick.ast.TypedType
import slick.ast.Library.SqlFunction
import slick.jdbc.JdbcProfile
import slick.lifted.{ExtensionMethods, OptionMapperDSL, SimpleExpression}

import scala.language.implicitConversions

/** Typed expressions for DuckDB's JSON scalar functions. */
trait DuckDBJsonScalarApi[J] extends DuckDBJsonApi[J] {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonUbigintJdbcType: BaseColumnType[BigInt]
  implicit def jsonKeysJdbcType: BaseColumnType[Seq[String]]

  private val arrayLengthFunction = new SqlFunction("json_array_length")
  private val containsFunction = new SqlFunction("json_contains")
  private val keysFunction = new SqlFunction("json_keys")
  private val unnestFunction = new SqlFunction("unnest")
  private val prettyFunction = new SqlFunction("json_pretty")
  private val structureFunction = new SqlFunction("json_structure")
  private val typeFunction = new SqlFunction("json_type")
  private val validFunction = new SqlFunction("json_valid")
  private val parseFunction = new SqlFunction("json")

  implicit def jsonScalarColumnExtensionMethods(c: Rep[J]): JsonScalarColumnExtensionMethods[J] =
    new JsonScalarColumnExtensionMethods(c)

  implicit def jsonScalarOptionColumnExtensionMethods(c: Rep[Option[J]]): JsonScalarColumnExtensionMethods[Option[J]] =
    new JsonScalarColumnExtensionMethods(c)

  class JsonScalarColumnExtensionMethods[P1](val c: Rep[P1]) extends ExtensionMethods[J, P1] {
    protected implicit def b1Type: TypedType[J] = jsonJdbcType

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

    /** Expand the immediate elements of a JSON array in a SELECT projection. */
    def jsonArrayElements: Rep[J] =
      expandArray("json_extract", b1Type)

    /** Expand array elements as text in a SELECT projection. JSON null yields None. */
    def jsonArrayElementsText: Rep[Option[String]] =
      expandArray("json_extract_string", implicitly[TypedType[String]].optionType)

    /** Expand the keys of a JSON object in a SELECT projection. */
    def jsonObjectKeys: Rep[String] = {
      val keys = keysFunction.typed(jsonKeysJdbcType, n)
      Rep.forNode[String](unnestFunction.typed(implicitly[TypedType[String]], keys))
    }

    private def expandArray[R](extract: String, resultType: TypedType[R]): Rep[R] =
      SimpleExpression.unary[P1, R] { (document, qb) =>
        qb.sqlBuilder += "unnest(" += extract += "("
        qb.expr(document, true)
        qb.sqlBuilder += ", '$[*]'))"
      }(resultType)(c)

    def jsonPretty[R](implicit om: o#to[String, R]): Rep[R] =
      om.column(prettyFunction, n)

    def jsonStructure[R](implicit om: o#to[J, R]): Rep[R] =
      om.column(structureFunction, n)

    def jsonType[R](implicit om: o#to[String, R]): Rep[R] =
      om.column(typeFunction, n)

    def jsonType(path: JsonScalarPath): Rep[Option[String]] =
      atPath[String](typeFunction, path)
  }

  /** Validate raw SQL text, returning false for malformed JSON. */
  def jsonValid[P, R](raw: Rep[P])(implicit
      om: OptionMapperDSL.arg[String, P]#to[Boolean, R]
  ): Rep[R] = om.column(validFunction, raw.toNode)

  /** Parse and minify raw SQL text as JSON. Malformed input raises a SQL error. */
  def jsonParse[P, R](raw: Rep[P])(implicit
      om: OptionMapperDSL.arg[String, P]#to[J, R]
  ): Rep[R] = om.column(parseFunction, raw.toNode)
}
