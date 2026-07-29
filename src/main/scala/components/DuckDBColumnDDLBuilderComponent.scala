package components

import slick.SlickException
import slick.ast.*
import slick.compiler.{CompilerState, QueryCompiler}
import slick.jdbc.JdbcProfile
import slick.lifted.{
  SimpleBinaryOperator,
  SimpleExpression,
  SimpleFunction,
  SimpleLiteral
}
import utils.UtilityFunctions.getBackingSequenceName

trait DuckDBColumnDDLBuilderComponent {
  self: JdbcProfile & DuckDBQueryBuilderComponent =>

  /** Builder for the column definition parts of DDL statements.
    *
    * Customizes how column options are appended to the SQL statement,
    * particularly for auto-increment columns which require a backing sequence.
    */
  class DuckDBColumnDDLBuilder(column: FieldSymbol, table: Table[?])
      extends ColumnDDLBuilder(column) {

    private lazy val backingSequenceName: String =
      getBackingSequenceName(table.tableName, column.name)

    private lazy val checkOptions =
      column.options.collect { case check: DuckDBColumnOption.Check[?] =>
        check
      }

    private lazy val ddlColumnNames: Set[String] =
      table.create_*.iterator.map(_.name).toSet

    override protected def handleColumnOption(
        option: ColumnOption[?]
    ): Unit =
      option match {
        case _: DuckDBColumnOption.Check[?] => ()
        case _                              => super.handleColumnOption(option)
      }

    private def appendLengthCheck(sb: StringBuilder): Unit =
      size.foreach { maximum =>
        sb append " CHECK (length("
        sb append quoteIdentifier(column.name)
        sb append ") <= " append maximum append ")"
      }

    private def checkError(
        check: DuckDBColumnOption.Check[?],
        detail: String
    ): SlickException =
      new SlickException(
        s"""DuckDB check constraint "${check.name}" on column "${column.name}" $detail"""
      )

    private def sameTable(candidate: TableNode): Boolean =
      candidate == table.tableNode

    private def validateCheckName(check: DuckDBColumnOption.Check[?]): Unit =
      if (check.name == null || check.name.trim.isEmpty)
        throw checkError(check, "must have a non-empty name")

    private def validateBooleanResult(
        check: DuckDBColumnOption.Check[?],
        predicate: Node
    ): Unit = {
      val resultType = predicate.nodeType.structural
      val baseType   =
        resultType match {
          case option: OptionType => option.elementType
          case other              => other
        }

      val isBoolean =
        try jdbcTypeFor(baseType).scalaType == ScalaBaseType.booleanType
        catch {
          case _: SlickException => false
        }

      if (!isBoolean)
        throw checkError(
          check,
          s"must return Boolean or Option[Boolean], but returned $resultType"
        )
    }

    private def validateLiteral(
        check: DuckDBColumnOption.Check[?],
        literal: LiteralNode
    ): Unit = {
      if (literal.volatileHint)
        throw checkError(check, "cannot contain JDBC bind parameters")

      val literalType =
        if (literal.hasType) literal.nodeType else literal.buildType

      val hasLiteralForm =
        try jdbcTypeFor(literalType).hasLiteralForm
        catch {
          case _: SlickException => false
        }

      if (!hasLiteralForm)
        throw checkError(
          check,
          s"cannot inline a literal of type $literalType"
        )
    }

    private def validateFunctionName(
        check: DuckDBColumnOption.Check[?],
        function: SimpleFunction
    ): Unit =
      if (!function.name.matches("[A-Za-z_][A-Za-z0-9_]*"))
        throw checkError(
          check,
          s"""cannot contain unsafe function name "${function.name}""""
        )

    private def validatePredicateNode(
        check: DuckDBColumnOption.Check[?],
        node: Node
    ): Unit =
      node match {
        case Select(tableNode: TableNode, field: FieldSymbol) =>
          if (!sameTable(tableNode))
            throw checkError(
              check,
              s"""cannot reference column "${field.name}" from another table"""
            )
          if (!ddlColumnNames.contains(field.name))
            throw checkError(
              check,
              s"""cannot reference column "${field.name}" because it is not included in the table DDL"""
            )

        case literal: LiteralNode =>
          validateLiteral(check, literal)

        case Apply(symbol: Library.AggregateFunctionSymbol, _) =>
          throw checkError(
            check,
            s"cannot contain aggregate function ${symbol.toString}"
          )

        case Apply(symbol, _)
            if symbol == Library.Exists ||
              symbol == Library.NextValue ||
              symbol == Library.CurrentValue =>
          throw checkError(check, s"cannot contain ${symbol.toString}")

        case Apply(symbol, children)
            if symbol.isInstanceOf[Library.SqlOperator] ||
              symbol.isInstanceOf[Library.SqlFunction] ||
              symbol.isInstanceOf[Library.JdbcFunction] ||
              symbol == Library.Between ||
              symbol == Library.Cast ||
              symbol == Library.SilentCast ||
              symbol == Library.Substring ||
              symbol == Library.Trim ||
              symbol == Library.IndexOf ||
              symbol == Library.Like ||
              symbol == Library.StartsWith ||
              symbol == Library.EndsWith =>
          children.foreach(validatePredicateNode(check, _))

        case OptionApply(child) =>
          validatePredicateNode(check, child)

        case conditional: IfThenElse =>
          conditional.children.foreach(validatePredicateNode(check, _))

        case product: ProductNode =>
          product.children.foreach(validatePredicateNode(check, _))

        case function: SimpleFunction =>
          validateFunctionName(check, function)
          function.children.foreach(validatePredicateNode(check, _))

        case _: QueryParameter =>
          throw checkError(check, "cannot contain JDBC bind parameters")

        case _: Ref =>
          throw checkError(check, "cannot contain query aliases or paths")

        case _: SequenceNode =>
          throw checkError(check, "cannot contain sequence references")

        case _: OptionFold =>
          throw checkError(check, "cannot contain OptionFold or getOrElse")

        case _: RowNumber =>
          throw checkError(check, "cannot contain window functions")

        case _: SimpleLiteral =>
          throw checkError(check, "cannot contain raw SQL literals")

        case _: SimpleExpression =>
          throw checkError(check, "cannot contain raw SQL expressions")

        case _: SimpleBinaryOperator =>
          throw checkError(check, "cannot contain raw SQL binary operators")

        case unsupported =>
          throw checkError(
            check,
            s"contains unsupported AST node ${unsupported.getClass.getSimpleName}"
          )
      }

    private def renderCheckPredicate(
        check: DuckDBColumnOption.Check[?]
    ): String =
      try {
        validateCheckName(check)

        val columnNode = Select(table.tableNode, column) :@ column.tpe
        val predicate  = check.predicateNode(columnNode)

        validatePredicateNode(check, predicate)
        val typedPredicate = predicate.infer()
        validateBooleanResult(check, typedPredicate)

        val state   = new CompilerState(QueryCompiler(), typedPredicate)
        val builder =
          new DuckDBCheckExpressionQueryBuilder(
            typedPredicate,
            state,
            table.tableNode
          )
        builder.buildSelect().sql
      } catch {
        case error: SlickException
            if Option(error.getMessage).exists(
              _.startsWith("DuckDB check constraint ")
            ) =>
          throw error
        case error: SlickException =>
          throw checkError(
            check,
            s"could not build its predicate: ${error.getMessage}"
          )
      }

    private def appendCheckConstraints(sb: StringBuilder): Unit =
      checkOptions.foreach { check =>
        sb append " CONSTRAINT "
        sb append quoteIdentifier(check.name)
        sb append " CHECK ("
        sb append renderCheckPredicate(check)
        sb append ")"
      }

    override protected def appendOptions(sb: StringBuilder): Unit = {
      if (autoIncrement)
        sb append " DEFAULT " append "nextval('" append backingSequenceName append "')"
      if (defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if (notNull) sb append " NOT NULL"
      if (primaryKey) sb append " PRIMARY KEY"
      if (unique) sb append " UNIQUE"
      appendLengthCheck(sb)
      appendCheckConstraints(sb)
    }
  }
}
