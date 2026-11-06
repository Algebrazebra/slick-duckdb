package duckdbslick.api

import duckdbslick.types.Json
import slick.SlickException
import slick.ast.{Aggregate, AnonSymbol, Bind, ElementSymbol, Library, Node, OptionType, ProductNode, Pure, Ref, Select, Type, TypedType}
import slick.jdbc.JdbcProfile
import slick.jdbc.JdbcType
import slick.lifted.Query

import scala.annotation.tailrec

/** DuckDB's JSON aggregates, exposed at the same query level as Slick's `sum`. */
trait DuckDBJsonAggregateApi {
  this: JdbcProfile#JdbcAPI =>

  implicit def jsonJdbcType: BaseColumnType[Json]

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

  private def requireJsonMapping[J](mapping: BaseColumnType[J]): Unit =
    if (!mapping.sqlTypeName(None).equalsIgnoreCase("JSON"))
      throw new SlickException("JSON aggregate result mapping must use SQL JSON")

  implicit def jsonAggregateQueryMethods[T, C[_]](
      query: Query[Rep[T], T, C]
  ): JsonAggregateQueryMethods[T, C] = new JsonAggregateQueryMethods(query)

  class JsonAggregateQueryMethods[T, C[_]](private val query: Query[Rep[T], T, C]) {
    /** Aggregate SQL values into a JSON array. Empty input yields SQL NULL. */
    def jsonGroupArray(implicit inputType: TypedType[T]): Rep[Option[Json]] =
      oneArgumentAggregate(groupArray, inputType, jsonJdbcType)

    /** Use an explicitly selected SQL JSON mapping for the result. */
    def jsonGroupArrayAs[J](mapping: BaseColumnType[J])(
        implicit inputType: TypedType[T]): Rep[Option[J]] =
      oneArgumentAggregate(groupArray, inputType, mapping)

    /** Combine JSON document structures across rows. Empty input yields SQL NULL. */
    def jsonGroupStructure(implicit input: JsonAggregateDocument[T, Json],
        inputType: TypedType[T]): Rep[Option[Json]] =
      oneArgumentAggregate(groupStructure, inputType, jsonJdbcType)

    /** Use an explicitly selected SQL JSON mapping for the result. */
    def jsonGroupStructureAs[J](mapping: BaseColumnType[J])(
        implicit input: JsonAggregateDocument[T, J], inputType: TypedType[T]): Rep[Option[J]] =
      oneArgumentAggregate(groupStructure, inputType, mapping)

    private def oneArgumentAggregate[J](function: Library.SqlFunction,
        inputType: TypedType[T], mapping: BaseColumnType[J]): Rep[Option[J]] = {
      requireJsonMapping(mapping)
      val aggregate = query.toNode match {
        case Bind(symbol, from, Pure(value, _)) =>
          Aggregate(symbol, from, function.typed(mapping.optionType,
            jsonOperand(value, inputType)))
        case from =>
          val symbol = new AnonSymbol
          Aggregate(symbol, from, function.typed(mapping.optionType,
            jsonOperand(Ref(symbol), inputType)))
      }
      Rep.forNode[Option[J]](aggregate)(mapping.optionType)
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
    def jsonGroupObject(implicit inputType: TypedType[T]): Rep[Option[Json]] =
      objectAggregate(jsonJdbcType, inputType)

    /** Use an explicitly selected SQL JSON mapping for the result. */
    def jsonGroupObjectAs[J](mapping: BaseColumnType[J])(
        implicit inputType: TypedType[T]): Rep[Option[J]] =
      objectAggregate(mapping, inputType)

    private def objectAggregate[J](mapping: BaseColumnType[J],
        inputType: TypedType[T]): Rep[Option[J]] = {
      requireJsonMapping(mapping)
      // Query.map creates a Bind over the original source. Build the aggregate
      // over that source, using its two projected expressions as arguments.
      // Slick's ordinary aggregate rewrite keeps only the first tuple field.
      val aggregate = query.toNode match {
        case Bind(symbol, from, Pure(ProductNode(args), _)) =>
          Aggregate(symbol, from, groupObject.typed(mapping.optionType,
            args(0), jsonOperand(args(1), inputType)))
        case from =>
          val symbol = new AnonSymbol
          Aggregate(symbol, from, groupObject.typed(mapping.optionType,
            Select(Ref(symbol), ElementSymbol(1)),
            jsonOperand(Select(Ref(symbol), ElementSymbol(2)), inputType)))
      }
      Rep.forNode[Option[J]](aggregate)(mapping.optionType)
    }
  }
}
