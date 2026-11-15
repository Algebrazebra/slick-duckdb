package duckdbslick.api

import slick.ast.{Aggregate, AnonSymbol, Bind, ElementSymbol, Library, Node, OptionType, ProductNode, Pure, Ref, Select, Type, TypedType}
import slick.jdbc.JdbcProfile
import slick.jdbc.JdbcType
import slick.lifted.Query

import scala.annotation.tailrec

/** DuckDB's JSON aggregates, exposed at the same query level as Slick's `sum`. */
trait DuckDBJsonAggregateApi[J] {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonJdbcType: BaseColumnType[J]

  private val groupArray = new Library.SqlFunction("json_group_array")
  private val groupObject = new Library.SqlFunction("json_group_object")
  private val groupStructure = new Library.SqlFunction("json_group_structure")

  @tailrec
  private def isJsonType(tpe: Type): Boolean = tpe match {
    case optional: OptionType => isJsonType(optional.elementType)
    case jdbc: JdbcType[?] => jdbc.sqlTypeName(None).equalsIgnoreCase("JSON")
    case _ => false
  }

  protected def jsonOperand(node: Node, tpe: Type): Node =
    if (isJsonType(tpe)) Library.Cast.typed(tpe, node, LiteralColumn("JSON").toNode)
    else node

  implicit def jsonAggregateQueryMethods[T, C[_]](
      query: Query[Rep[T], T, C]
  ): JsonAggregateQueryMethods[T, C] = new JsonAggregateQueryMethods(query)

  class JsonAggregateQueryMethods[T, C[_]](private val query: Query[Rep[T], T, C]) {
    /** Aggregate SQL values into a JSON array. Empty input yields SQL NULL. */
    def jsonGroupArray(implicit inputType: TypedType[T]): Rep[Option[J]] =
      oneArgumentAggregate(groupArray, inputType)

    /** Combine JSON document structures across rows. Empty input yields SQL NULL. */
    def jsonGroupStructure(implicit input: JsonAggregateDocument[T, J],
        inputType: TypedType[T]): Rep[Option[J]] =
      oneArgumentAggregate(groupStructure, inputType)

    private def oneArgumentAggregate(function: Library.SqlFunction,
        inputType: TypedType[T]): Rep[Option[J]] = {
      val aggregate = query.toNode match {
        case Bind(symbol, from, Pure(value, _)) =>
          Aggregate(symbol, from, function.typed(jsonJdbcType.optionType,
            jsonOperand(value, inputType)))
        case from =>
          val symbol = new AnonSymbol
          Aggregate(symbol, from, function.typed(jsonJdbcType.optionType,
            jsonOperand(Ref(symbol), inputType)))
      }
      Rep.forNode[Option[J]](aggregate)(jsonJdbcType.optionType)
    }
  }

  /** Evidence that structure input uses the same JSON representation as its result. */
  sealed trait JsonAggregateDocument[T, J]
  implicit def jsonAggregateDocument[J]: JsonAggregateDocument[J, J] =
    new JsonAggregateDocument[J, J] {}
  implicit def optionalJsonAggregateDocument[J]: JsonAggregateDocument[Option[J], J] =
    new JsonAggregateDocument[Option[J], J] {}

  implicit def jsonObjectAggregateQueryMethods[T, C[_]](
      query: Query[(Rep[String], Rep[T]), (String, T), C]
  ): JsonObjectAggregateQueryMethods[T, C] = new JsonObjectAggregateQueryMethods(query)

  class JsonObjectAggregateQueryMethods[T, C[_]](
      private val query: Query[(Rep[String], Rep[T]), (String, T), C]
  ) {
    /** Aggregate non-null string keys and SQL values into a JSON object. */
    def jsonGroupObject(implicit inputType: TypedType[T]): Rep[Option[J]] =
      objectAggregate(inputType)

    private def objectAggregate(inputType: TypedType[T]): Rep[Option[J]] = {
      // Query.map creates a Bind over the original source. Build the aggregate
      // over that source, using its two projected expressions as arguments.
      // Slick's ordinary aggregate rewrite keeps only the first tuple field.
      val aggregate = query.toNode match {
        case Bind(symbol, from, Pure(ProductNode(args), _)) =>
          Aggregate(symbol, from, groupObject.typed(jsonJdbcType.optionType,
            args(0), jsonOperand(args(1), inputType)))
        case from =>
          val symbol = new AnonSymbol
          Aggregate(symbol, from, groupObject.typed(jsonJdbcType.optionType,
            Select(Ref(symbol), ElementSymbol(1)),
            jsonOperand(Select(Ref(symbol), ElementSymbol(2)), inputType)))
      }
      Rep.forNode[Option[J]](aggregate)(jsonJdbcType.optionType)
    }
  }
}
