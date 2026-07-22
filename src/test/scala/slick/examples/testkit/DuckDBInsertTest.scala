package slick.examples.testkit

import com.typesafe.slick.testkit.tests.InsertTest
import slick.jdbc.JdbcCapabilities

import scala.util.Failure

class DuckDBInsertTest extends InsertTest {

  import tdb.profile.api.*

  def testLengthCheckDDL = {
    class LengthOptions(tag: Tag)
        extends Table[(String, Option[String], String, String, String, String)](
          tag,
          "length_options"
        ) {
      def name         = column[String]("name", O.Length(2))
      def nullableName =
        column[Option[String]]("nullable_name", O.Length(2))
      def uniqueName   = column[String]("unique_name", O.Length(2), O.Unique)
      def defaultName  =
        column[String]("default_name", O.Length(2), O.Default("x"))
      def fixedName    = column[String]("fixed_name", O.Length(2, varying = false))
      def reservedName = column[String]("select", O.Length(2))
      def *            =
        (name, nullableName, uniqueName, defaultName, fixedName, reservedName)
    }
    val lengthOptions = TableQuery[LengthOptions]

    val createStatement            = lengthOptions.schema.createStatements.mkString(" ")
    val createIfNotExistsStatement =
      lengthOptions.schema.createIfNotExistsStatements.mkString(" ")
    Seq(createStatement, createIfNotExistsStatement).foreach { statement =>
      statement.contains(
        "\"name\" VARCHAR(2) NOT NULL CHECK (length(\"name\") <= 2)"
      ) shouldBe true
      statement.contains(
        "\"nullable_name\" VARCHAR(2) CHECK (length(\"nullable_name\") <= 2)"
      ) shouldBe true
      statement.contains(
        "\"unique_name\" VARCHAR(2) NOT NULL UNIQUE CHECK (length(\"unique_name\") <= 2)"
      ) shouldBe true
      statement.contains(
        "\"default_name\" VARCHAR(2) DEFAULT 'x' NOT NULL CHECK (length(\"default_name\") <= 2)"
      ) shouldBe true
      statement.contains(
        "\"fixed_name\" CHAR(2) NOT NULL CHECK (length(\"fixed_name\") <= 2)"
      ) shouldBe true
      statement.contains(
        "\"select\" VARCHAR(2) NOT NULL CHECK (length(\"select\") <= 2)"
      ) shouldBe true
    }

    DBIO.seq(
      lengthOptions.schema.createIfNotExists,
      lengthOptions.schema.createIfNotExists
    )
  }

  def testLengthCheckBoundaryNullableAndUnicode = {
    class LengthValues(tag: Tag)
        extends Table[(Int, Option[String])](tag, "length_values") {
      def id   = column[Int]("id", O.PrimaryKey)
      def name =
        column[Option[String]]("name", O.Length(2))
      def *    = (id, name)
    }
    val lengthValues = TableQuery[LengthValues]

    DBIO.seq(
      lengthValues.schema.create,
      lengthValues ++= Seq(
        (1, Some("")),
        (2, Some("ab")),
        (3, None),
        (4, Some("é界"))
      ),
      (lengthValues += ((5, Some("abc")))).asTry.map(_.isFailure shouldBe true),
      lengthValues
        .sortBy(_.id)
        .result
        .map(
          _ shouldBe Seq(
            (1, Some("")),
            (2, Some("ab")),
            (3, None),
            (4, Some("é界"))
          )
        )
    )
  }

  def testLengthCheckFixedQuotedUniqueAndDefaults = {
    class FixedValues(tag: Tag)
        extends Table[(Int, String, String)](tag, "fixed_values") {
      def id       = column[Int]("id", O.PrimaryKey)
      def fixed    = column[String]("fixed", O.Length(2, varying = false))
      def reserved = column[String]("select", O.Length(2), O.Unique)
      def *        = (id, fixed, reserved)
    }
    val fixedValues = TableQuery[FixedValues]

    class DefaultValues(tag: Tag)
        extends Table[(Int, String)](tag, "default_values") {
      def id   = column[Int]("id", O.PrimaryKey)
      def name = column[String]("name", O.Length(2), O.Default("x"))
      def *    = (id, name)
    }
    val defaultValues = TableQuery[DefaultValues]

    class OversizedDefaultValues(tag: Tag)
        extends Table[(Int, String)](tag, "oversized_default_values") {
      def id   = column[Int]("id", O.PrimaryKey)
      def name = column[String]("name", O.Length(2), O.Default("abc"))
      def *    = (id, name)
    }
    val oversizedDefaultValues = TableQuery[OversizedDefaultValues]

    DBIO.seq(
      fixedValues.schema.create,
      fixedValues += ((1, "ab", "if")),
      (fixedValues += ((2, "abc", "or"))).asTry.map(_.isFailure shouldBe true),
      (fixedValues += ((3, "ok", "if"))).asTry.map(_.isFailure shouldBe true),
      defaultValues.schema.create,
      defaultValues.map(_.id) += 1,
      defaultValues.result.map(_ shouldBe Seq((1, "x"))),
      oversizedDefaultValues.schema.create,
      (oversizedDefaultValues.map(_.id) += 1).asTry.map(
        _.isFailure shouldBe true
      )
    )
  }

  // When DuckDB returns the affected rows count, a single row update is counted as one affected row.
  // The parent test assumes that a single updated row is counted as two affected rows (delete + insert).
  override def testInsertOrUpdateAll = {
    class T(tag: Tag) extends Table[(Int, String)](tag, "insert_or_update") {
      def id   = column[Int]("id", O.PrimaryKey)
      def name = column[String]("name")
      def *    = (id, name)
      def ins  = (id, name)
    }
    val ts = TableQuery[T]
    def prepare = DBIO.seq(ts.schema.create, ts ++= Seq((1, "a"), (2, "b")))
    if (tdb.capabilities.contains(JdbcCapabilities.insertOrUpdate)) {
      for {
        _ <- prepare
        // We override this test purely to set the affected rows to 2 instead of 3.
        // Some JDBC drivers count an update to a row as 2 affected rows instead of 1.
        // Maybe this is because they count an update as 1 delete operation and 1 insert operation.
        // The default Slick tests assume this behavior.
        // DuckDB counts an update as only one affected row, hence the override.
        _ <- ts.insertOrUpdateAll(Seq((3, "c"), (1, "d")))
               .map(_.foreach(_ shouldBe 2))
        _ <- ts.sortBy(_.id)
               .result
               .map(_ shouldBe Seq((1, "d"), (2, "b"), (3, "c")))
      } yield ()
    } else
      {
        for {
          _ <- prepare
          _ <- ts.insertOrUpdateAll(Seq((3, "c"), (1, "d")))
        } yield ()
      }.asTry.map {
        case Failure(exception) =>
          exception.isInstanceOf[SlickException] shouldBe true
        case _                  =>
          throw new RuntimeException(
            "Should insertOrUpdateAll is not supported for this profile."
          )
      }
  }

  // The parent test hardcodes an SQL statement with syntax that is invalid for DuckDB.
  // Instead of creating the `CTABLE` and `DTABLE` with a hardcoded SQL statement,
  // the test is adapted to use the native Slick way: `{c, d}.schema.create`
  override def testInsertOrUpdateWithInsertedWhen0IsSpecifiedForAutoInc
      : DBIOAction[Unit, NoStream, Effect.All] =
    if (!tdb.profile.capabilities.contains(JdbcCapabilities.insertOrUpdate))
      DBIO.successful(())
    else {
      case class C(id1: Int, id2: Int)
      class CTable(tag: Tag) extends Table[C](tag, "CTABLE") {
        def id1 = column[Int]("id1", O.AutoInc)

        def id2 = column[Int]("id2")

        val pk = primaryKey("pk_for_ctable", (id1, id2))

        def * = (id1, id2) <> ((C.apply _).tupled, C.unapply)
      }
      case class D(id1: Int, id2: Int, v: Int)
      class DTable(tag: Tag) extends Table[D](tag, "DTABLE") {
        def id1 = column[Int]("id1", O.AutoInc)

        def id2 = column[Int]("id2")

        def v = column[Int]("v")

        val pk = primaryKey("pk_for_dtable", (id1, id2))

        def * = (id1, id2, v) <> ((D.apply _).tupled, D.unapply)
      }
      case class F(id: Int)
      class FTable(tag: Tag) extends Table[F](tag, "FTABLE") {
        def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
        def *  = (id) <> (F.apply, (f: F) => Option(f.id))
      }
      val c = TableQuery[CTable]
      val d = TableQuery[DTable]
      val f = TableQuery[FTable]
      for {
        _    <- c.schema.create
        _    <- c.insertOrUpdate(C(0, 1))    // inserted
        _    <- c.insertOrUpdate(C(0, 1))    // inserted
        allC <- c.result
        _    <- d.schema.create
        _    <- d.insertOrUpdate(D(0, 0, 1)) // inserted
        _    <- d.insertOrUpdate(D(0, 0, 2)) // inserted
        _    <- d.insertOrUpdate(D(0, 0, 2)) // inserted
        _    <- d.insertOrUpdate(D(1, 0, 1)) // updated
        allD <- d.result
        _    <- f.schema.create
        _    <- f.insertOrUpdate(F(0))       // inserted
        _    <- f.insertOrUpdate(F(0))       // inserted
        allF <- f.result
      } yield {
        allC.toSet shouldBe Set(C(1, 1), C(2, 1))
        allD.toSet shouldBe Set(D(1, 0, 1), D(2, 0, 2), D(3, 0, 2))
        allF.toSet shouldBe Set(F(1), F(2))
      }
    }
}
