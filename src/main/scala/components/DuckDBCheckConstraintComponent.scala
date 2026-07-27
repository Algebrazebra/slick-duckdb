package components

import slick.ast.{ColumnOption, Node, TypedType}
import slick.jdbc.JdbcProfile
import slick.lifted.{CanBeQueryCondition, Rep}

object DuckDBColumnOption {
  final case class Check[T](
      name: String,
      predicateNode: Node => Node
  ) extends ColumnOption[T]
}

trait DuckDBCheckConstraintComponent {
  self: JdbcProfile =>

  trait DuckDBColumnOptions extends SqlColumnOptions {
    final class CheckBuilder[T](
        name: String
    ) {

      /** Build a DuckDB check constraint.
        *
        * `TypedType[T]` is requested here, rather than by `Check`, so Scala 2
        * parses the second argument list in `O.Check[T](name)(predicate)` as
        * this builder invocation. The type is still captured when the column
        * option is declared and stored in the node-building closure.
        *
        * The predicate is schema-definition code and may be evaluated more
        * than once while Slick builds schema descriptions. It must therefore
        * be deterministic, side-effect-free, and independent of mutable
        * application state.
        */
      def apply[P <: Rep[?]](
          predicate: Rep[T] => P
      )(implicit
          typedType: TypedType[T],
          condition: CanBeQueryCondition[P]
      ): ColumnOption[T] =
        DuckDBColumnOption.Check[T](
          name,
          columnNode =>
            condition(
              predicate(Rep.forNode[T](columnNode)(typedType))
            ).toNode
        )
    }

    def Check[T](name: String): CheckBuilder[T] =
      new CheckBuilder[T](name)
  }

  override val columnOptions: DuckDBColumnOptions =
    new DuckDBColumnOptions {}
}
