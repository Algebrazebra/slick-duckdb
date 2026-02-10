package components

import slick.ast.*
import slick.compiler.CompilerState
import slick.jdbc.JdbcProfile
import slick.util.QueryInterpolator.queryInterpolator

trait DuckDBQueryBuilderComponent {
  self: JdbcProfile =>

  /** Builder for SELECT and UPDATE statements.
    *
    * Methods and attributes are overridden where the Slick default
    * implementation either provides behavior incompatible with DuckDB or
    * doesn't provide an implementation.
    */
  class DuckDBQueryBuilder(n: Node, state: CompilerState)
      extends self.QueryBuilder(n, state) {

    override protected val concatOperator: Option[String]                   = Some("||")
    override protected val quotedJdbcFns: Option[Seq[Library.JdbcFunction]] =
      Some(Seq.empty)

    /** Here the parent implementation is overridden to map Slick's generic
      * expression nodes from the [[Library]] to the corresponding
      * DuckDB expressions.
      */
    override def expr(n: Node): Unit = n match {
      case Library.IfNull(ch, d)            => b"ifnull($ch, $d)"
      case Library.Length(ch)               => b"length($ch)"
      case Library.Database()               => b"current_database()"
      case Library.User()                   => b"current_user()"
      case Library.IndexOf(n, str)          => b"strpos($n, $str) - 1"
      case Library.Substring(n, start, end) =>
        val startNode  =
          QueryParameter.constOp[Int]("+")(_ + _)(start, LiteralNode(1).infer())
        val lengthNode = QueryParameter.constOp[Int]("-")(_ - _)(end, start)
        b"substring($n, $startNode, $lengthNode)"
      case Library.Substring(n, start)      =>
        val startNode =
          QueryParameter.constOp[Int]("+")(_ + _)(start, LiteralNode(1).infer())
        b"substring($n, $startNode)"

      // Since the DuckDB dialect is widely compatible with Postgres SQL by design,
      // the following function mappings are taken directly from the Postgres Profile.
      case Library.UCase(ch)                        => b"upper($ch)"
      case Library.LCase(ch)                        => b"lower($ch)"
      case Library.NextValue(SequenceNode(name))    => b"nextval('$name')"
      case Library.CurrentValue(SequenceNode(name)) => b"currval('$name')"
      case Library.CurrentDate()                    => b"current_date"
      case Library.CurrentTime()                    => b"current_time"

      case _ => super.expr(n)
    }

    /** The parent implementation implements fetching t results with an offset d
      * with syntax prescribed by the SQL 2008 standard. DuckDB supports a more
      * modern syntax combining limit and offset clauses.
      */
    override protected def buildFetchOffsetClause(
        fetch: Option[Node],
        offset: Option[Node]
    ): Unit =
      (fetch, offset) match {
        case (Some(t), Some(d)) => b"\nlimit $t offset $d"
        case (Some(t), None)    => b"\nlimit $t"
        case (None, Some(d))    => b"\noffset $d"
        case _                  => ()
      }
  }

}
