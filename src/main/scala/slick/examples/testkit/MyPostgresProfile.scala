//#outline
package slick.examples.testkit

//#outline
import java.sql.{PreparedStatement, ResultSet}
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import slick.ast.*
import slick.basic.Capability
import slick.compiler.{CompilerState, Phase}
import slick.dbio.*
import slick.jdbc.*
import slick.jdbc.meta.{MColumn, MIndexInfo, MTable}
import slick.relational.RelationalProfile
import slick.util.ConstArray
import slick.util.QueryInterpolator.queryInterpolator

/** Slick profile for PostgreSQL.
  *
  * This profile implements [[slick.jdbc.JdbcProfile]] ''without'' the following
  * capabilities:
  *
  * <ul> <li>[[slick.jdbc.JdbcCapabilities.insertOrUpdate]]: InsertOrUpdate
  * operations are emulated on the server side with a single JDBC statement
  * executing multiple server-side statements in a transaction. This is faster
  * than a client-side emulation but may still fail due to concurrent updates.
  * InsertOrUpdate operations with `returning` are emulated on the client
  * side.</li>
  * <li>[[slick.jdbc.JdbcCapabilities.insertOrUpdateWithPrimaryKeyOnly]]
  * <li>[[slick.jdbc.JdbcCapabilities.nullableNoDefault]]: Nullable columns
  * always have NULL as a default according to the SQL standard. Consequently
  * Postgres treats no specifying a default value just as specifying NULL and
  * reports NULL as the default value. Some other dbms treat queries with no
  * default as NULL default, but distinguish NULL from no default value in the
  * meta data.</li> <li>[[slick.jdbc.JdbcCapabilities.supportsByte]]: Postgres
  * doesn't have a corresponding type for Byte. SMALLINT is used instead and
  * mapped to Short in the Slick model.</li> </ul>
  *
  * Notes:
  *
  * <ul> <li>[[slick.relational.RelationalCapabilities.typeBlob]]: The default
  * implementation of the <code>Blob</code> type uses the database type
  * <code>lo</code> and the stored procedure <code>lo_manage</code>, both of
  * which are provided by the "lo" extension in PostgreSQL.</li> </ul>
  */
//#outline
trait MyPostgresProfile
    extends JdbcProfile
    with JdbcActionComponent.MultipleRowsPerStatementSupport {
  // ...
  // #outline

  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities -
      JdbcCapabilities.insertOrUpdate -
      JdbcCapabilities.insertOrUpdateWithPrimaryKeyOnly -
      JdbcCapabilities.nullableNoDefault -
      JdbcCapabilities.supportsByte

  private class ModelBuilder(
      mTables: Seq[MTable],
      ignoreInvalidDefaults: Boolean
  )(implicit
      ec: ExecutionContext
  ) extends JdbcModelBuilder(mTables, ignoreInvalidDefaults) {
    override def createTableNamer(mTable: MTable): TableNamer = new TableNamer(
      mTable
    ) {
      override def schema =
        super.schema.filter(_ != "public") // remove default schema
    }
    override def createColumnBuilder(
        tableBuilder: TableBuilder,
        meta: MColumn
    ): ColumnBuilder =
      new ColumnBuilder(tableBuilder, meta) {
        val NumericPattern               =
          "^['(]?(-?[0-9]+\\.?[0-9]*)[')]?(?:::(?:numeric|bigint|integer))?".r
        val TextPattern                  = "^'(.*)'::(?:bpchar|character varying|text)".r
        val UUIDPattern                  = "^'(.*)'::uuid".r
        override def default             = meta.columnDef
          .map((_, tpe))
          .collect {
            case ("true", "Boolean")                          => Some(Some(true))
            case ("false", "Boolean")                         => Some(Some(false))
            case (TextPattern(str), "String")                 => Some(Some(str))
            case ("NULL::bpchar", "String")                   => Some(None)
            case (TextPattern(str), "Char")                   =>
              str.length match {
                case 0 =>
                  Some(
                    Some(' ')
                  ) // Default to one space, as the char will be space padded anyway
                case 1 => Some(Some(str.head))
                case _ =>
                  None // This is invalid, so let's not supply any default
              }
            case ("NULL::bpchar", "Char")                     => Some(None)
            case (NumericPattern(v), "Short")                 => Some(Some(v.toShort))
            case (NumericPattern(v), "Int")                   => Some(Some(v.toInt))
            case (NumericPattern(v), "Long")                  => Some(Some(v.toLong))
            case (NumericPattern(v), "Float")                 => Some(Some(v.toFloat))
            case (NumericPattern(v), "Double")                => Some(Some(v.toDouble))
            case (NumericPattern(v), "scala.math.BigDecimal") =>
              Some(Some(BigDecimal(s"$v")))
            case (UUIDPattern(v), "java.util.UUID")           =>
              Some(Some(java.util.UUID.fromString(v)))
            case (_, "java.util.UUID")                        =>
              None // The UUID is generated through a function - treat it as if there was no default.
          }
          .getOrElse {
            val d = super.default
            if (meta.nullable.contains(true) && d.isEmpty) {
              Some(None)
            } else d
          }
        override def length: Option[Int] = {
          val l = super.length
          if (tpe == "String" && varying && l.contains(2147483647)) None
          else l
        }
        override def tpe                 = meta.typeName match {
          case "bytea"                                         => "Array[Byte]"
          case "lo" if meta.sqlType == java.sql.Types.DISTINCT =>
            "java.sql.Blob"
          case "uuid"                                          => "java.util.UUID"
          case _                                               => super.tpe
        }
      }
    override def createIndexBuilder(
        tableBuilder: TableBuilder,
        meta: Seq[MIndexInfo]
    ): IndexBuilder =
      new IndexBuilder(tableBuilder, meta) {
        override def columns =
          super.columns.map(_.stripPrefix("\"").stripSuffix("\""))
      }
  }

  override def createModelBuilder(
      tables: Seq[MTable],
      ignoreInvalidDefaults: Boolean
  )(implicit ec: ExecutionContext): JdbcModelBuilder =
    new ModelBuilder(tables, ignoreInvalidDefaults)

  override def defaultTables(implicit ec: ExecutionContext): DBIO[Seq[MTable]] =
    MTable.getTables(None, None, None, Some(Seq("TABLE")))

  override val columnTypes: MyJdbcTypes                                  = new MyJdbcTypes
  override protected def computeQueryCompiler                            =
    super.computeQueryCompiler - Phase.rewriteDistinct
  override def createQueryBuilder(
      n: Node,
      state: CompilerState
  ): MyQueryBuilder =
    new MyQueryBuilder(n, state)
  override def createUpsertBuilder(node: Insert): InsertBuilder          =
    new MyUpsertBuilder(node)
  override def createTableDDLBuilder(table: Table[?]): MyTableDDLBuilder =
    new MyTableDDLBuilder(table)
  override def createColumnDDLBuilder(
      column: FieldSymbol,
      table: Table[?]
  ): MyColumnDDLBuilder =
    new MyColumnDDLBuilder(column)
  override protected lazy val useServerSideUpsert                        = true
  override protected lazy val useTransactionForUpsert                    = true
  override protected lazy val useServerSideUpsertReturning               = false

  override def defaultSqlTypeName(
      tmd: JdbcType[?],
      sym: Option[FieldSymbol]
  ): String = tmd.sqlType match {
    case java.sql.Types.VARCHAR =>
      val size =
        sym.flatMap(_.findColumnOption[RelationalProfile.ColumnOption.Length])
      size.fold("VARCHAR")(l =>
        if (l.varying) s"VARCHAR(${l.length})" else s"CHAR(${l.length})"
      )
    case java.sql.Types.BLOB    => "lo"
    case java.sql.Types.DOUBLE  => "DOUBLE PRECISION"
    /* PostgreSQL does not have a TINYINT type, so we use SMALLINT instead. */
    case java.sql.Types.TINYINT => "SMALLINT"
    case _                      => super.defaultSqlTypeName(tmd, sym)
  }

  class MyQueryBuilder(tree: Node, state: CompilerState)
      extends QueryBuilder(tree, state) {
    override protected val concatOperator: Option[String]                   = Some("||")
    override protected val quotedJdbcFns: Option[Seq[Library.JdbcFunction]] =
      Some(
        Vector(Library.Database, Library.User)
      )

    override protected def buildSelectModifiers(c: Comprehension.Base): Unit =
      (c.distinct, c.select) match {
        case (Some(ProductNode(onNodes)), Pure(ProductNode(selNodes), _))
            if onNodes.nonEmpty =>
          def eligible(a: ConstArray[Node]) = a.forall {
            case _: PathElement    => true
            case _: LiteralNode    => true
            case _: QueryParameter => true
            case _                 => false
          }
          if (
            eligible(onNodes) && eligible(selNodes) &&
            onNodes.iterator
              .collect[List[TermSymbol]] { case FwdPath(ss) => ss }
              .toSet ==
              selNodes.iterator
                .collect[List[TermSymbol]] { case FwdPath(ss) => ss }
                .toSet
          ) b"distinct "
          else super.buildSelectModifiers(c)
        case _ =>
          super
            .buildSelectModifiers(c)
      }

    override protected def buildFetchOffsetClause(
        fetch: Option[Node],
        offset: Option[Node]
    ): Unit = (fetch, offset) match {
      case (Some(t), Some(d)) => b"\nlimit $t offset $d"
      case (Some(t), None)    => b"\nlimit $t"
      case (None, Some(d))    => b"\noffset $d"
      case _                  =>
    }

    override def expr(n: Node) = n match {
      case Library.UCase(ch)                        => b"upper($ch)"
      case Library.LCase(ch)                        => b"lower($ch)"
      case Library.IfNull(ch, d)                    => b"coalesce($ch, $d)"
      case Library.NextValue(SequenceNode(name))    => b"nextval('$name')"
      case Library.CurrentValue(SequenceNode(name)) => b"currval('$name')"
      case Library.CurrentDate()                    => b"current_date"
      case Library.CurrentTime()                    => b"current_time"
      case _                                        => super.expr(n)
    }
  }

  private class MyUpsertBuilder(ins: Insert) extends UpsertBuilder(ins) {
    override def buildInsert: InsertBuilderResult = {
      val update          =
        "update " + tableName + " set " + softNames
          .map(n => s"$n=?")
          .mkString(",") +
          " where " + pkNames.map(n => s"$n=?").mkString(" and ")
      val nonAutoIncNames =
        nonAutoIncSyms.map(fs => quoteIdentifier(fs.name)).mkString(",")
      val nonAutoIncVars  = nonAutoIncSyms.map(_ => "?").mkString(",")
      val cond            = pkNames.map(n => s"$n=?").mkString(" and ")
      val insert          =
        s"insert into $tableName ($nonAutoIncNames) select $nonAutoIncVars" +
          s" where not exists (select 1 from $tableName where $cond)"
      new InsertBuilderResult(
        table,
        s"$update; $insert",
        ConstArray.from(softSyms ++ pkSyms)
      )
    }

    override def transformMapping(n: Node) =
      reorderColumns(n, softSyms ++ pkSyms ++ nonAutoIncSyms.toSeq ++ pkSyms)
  }

  class MyTableDDLBuilder(table: Table[?]) extends TableDDLBuilder(table) {
    override def createPhase1 = super.createPhase1 ++ columns.flatMap {
      case cb: MyColumnDDLBuilder => cb.createLobTrigger(table.tableName)
    }
    override def dropPhase1   = {
      val dropLobs = columns.flatMap { case cb: MyColumnDDLBuilder =>
        cb.dropLobTrigger(table.tableName)
      }
      if (dropLobs.isEmpty) super.dropPhase1
      else
        Seq(
          "delete from " + quoteIdentifier(table.tableName)
        ) ++ dropLobs ++ super.dropPhase1
    }
  }

  class MyColumnDDLBuilder(column: FieldSymbol)
      extends ColumnDDLBuilder(column) {
    override def appendColumn(sb: mutable.StringBuilder): Unit = {
      sb append quoteIdentifier(column.name) append ' '
      if (autoIncrement && !customSqlType) {
        sb append (if (sqlType.toUpperCase == "BIGINT") "BIGSERIAL"
                   else "SERIAL")
      } else appendType(sb)
      autoIncrement = false
      appendOptions(sb)
    }

    private def lobTrigger(tName: String) =
      quoteIdentifier(tName + "__" + quoteIdentifier(column.name) + "_lob")

    def createLobTrigger(tName: String): Option[String] =
      if (sqlType == "lo")
        Some(
          "create trigger " + lobTrigger(
            tName
          ) + " before update or delete on " +
            quoteIdentifier(
              tName
            ) + " for each row execute procedure lo_manage(" + quoteIdentifier(
              column.name
            ) + ")"
        )
      else None

    def dropLobTrigger(tName: String): Option[String] =
      if (sqlType == "lo")
        Some(
          "drop trigger " + lobTrigger(tName) + " on " + quoteIdentifier(tName)
        )
      else None
  }

  class MyJdbcTypes extends JdbcTypes {
    override val byteArrayJdbcType: MyByteArrayJdbcType =
      new MyByteArrayJdbcType
    override val uuidJdbcType: MyUUIDJdbcType           = new MyUUIDJdbcType

    class MyByteArrayJdbcType extends ByteArrayJdbcType {
      override val sqlType                               = java.sql.Types.BINARY
      override def sqlTypeName(sym: Option[FieldSymbol]) = "BYTEA"
    }

    class MyUUIDJdbcType extends UUIDJdbcType {
      override def sqlTypeName(sym: Option[FieldSymbol])             = "UUID"
      override def setValue(v: UUID, p: PreparedStatement, idx: Int) =
        p.setObject(idx, v, sqlType)
      override def getValue(r: ResultSet, idx: Int)                  =
        r.getObject(idx).asInstanceOf[UUID]
      override def updateValue(v: UUID, r: ResultSet, idx: Int)      =
        r.updateObject(idx, v)
      override def valueToSQLLiteral(value: UUID)                    = "'" + value + "'"
      override def hasLiteralForm                                    = true
    }
  }
  // #outline
}

object MyPostgresProfile extends MyPostgresProfile
//#outline
