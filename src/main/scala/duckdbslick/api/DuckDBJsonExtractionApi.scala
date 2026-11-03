package duckdbslick.api

import duckdbslick.types.{Json, JsonScalarPath}
import slick.ast.Library.{Cast, SqlFunction, SqlOperator}
import slick.ast.{FunctionSymbol, LiteralNode, Node, QueryParameter, TypedType}
import slick.jdbc.JdbcProfile
import slick.lifted.ExtensionMethods

import scala.language.implicitConversions

/** Typed JSON extraction expressions exposed by DuckDBProfile.api. */
trait DuckDBJsonExtractionApi extends DuckDBJsonApi {
  this: JdbcProfile#JdbcAPI =>

  implicit lazy val jsonScalarPathJdbcType: BaseColumnType[JsonScalarPath] =
    MappedColumnType.base[JsonScalarPath, String](_.value, JsonScalarPath(_))

  private val jsonExtractFunction = new SqlFunction("json_extract")
  private val jsonExtractTextFunction = new SqlFunction("json_extract_string")
  private val jsonValueFunction = new SqlFunction("json_value")
  private val jsonExistsFunction = new SqlFunction("json_exists")
  private val jsonArrowOperator = new SqlOperator("->")
  private val jsonArrowTextOperator = new SqlOperator("->>")

  implicit def jsonExtractionColumnExtensionMethods(
      c: Rep[Json]
  ): JsonExtractionColumnExtensionMethods[Json] =
    new JsonExtractionColumnExtensionMethods(c)

  implicit def jsonExtractionOptionColumnExtensionMethods(
      c: Rep[Option[Json]]
  ): JsonExtractionColumnExtensionMethods[Option[Json]] =
    new JsonExtractionColumnExtensionMethods(c)

  class JsonExtractionColumnExtensionMethods[P1](val c: Rep[P1])
      extends ExtensionMethods[Json, P1] {
    protected implicit def b1Type: TypedType[Json] = jsonJdbcType

    private val jsonResultType = jsonJdbcType.optionType
    private val textResultType = implicitly[TypedType[String]].optionType

    private def scalarPathNode[P2](path: Rep[P2]): Node = path.toNode match {
      case parameter: QueryParameter => parameter
      case literal: LiteralNode      => literal
      case _ =>
        throw new IllegalArgumentException(
          "JSON scalar extraction requires a validated literal or bound JsonScalarPath"
        )
    }

    private def jsonResult(function: FunctionSymbol, path: Node): Rep[Option[Json]] =
      Rep.forNode[Option[Json]](function.typed(jsonResultType, n, path))(jsonResultType)

    private def textResult(function: FunctionSymbol, path: Node): Rep[Option[String]] =
      Rep.forNode[Option[String]](function.typed(textResultType, n, path))(textResultType)

    def jsonExists[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonScalarPath, P2]#to[Boolean, R]
    ): Rep[R] = om.column(jsonExistsFunction, n, scalarPathNode(path))

    def jsonExtract[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonScalarPath, P2]#to[Json, R]
    ): Rep[Option[Json]] =
      jsonResult(jsonExtractFunction, scalarPathNode(path))

    def jsonExtractText[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonScalarPath, P2]#to[String, R]
    ): Rep[Option[String]] = textResult(jsonExtractTextFunction, scalarPathNode(path))

    def jsonValue[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonScalarPath, P2]#to[Json, R]
    ): Rep[Option[Json]] =
      Rep.forNode[Option[Json]](
        Cast.typed(
          jsonResultType,
          jsonValueFunction.typed(textResultType, n, scalarPathNode(path))
        )
      )(jsonResultType)

    def ->[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonScalarPath, P2]#to[Json, R]
    ): Rep[Option[Json]] =
      jsonResult(jsonArrowOperator, scalarPathNode(path))

    def ->>[P2, R](path: Rep[P2])(implicit
        om: o#arg[JsonScalarPath, P2]#to[String, R]
    ): Rep[Option[String]] = textResult(jsonArrowTextOperator, scalarPathNode(path))
  }
}
