package slick.examples.testkit

import com.typesafe.slick.testkit.tests.InsertTest
import slick.SlickException
import slick.jdbc.JdbcCapabilities

import scala.util.{Failure, Try}

class DuckDBInsertTest extends InsertTest {

  import tdb.profile.api.*

  def testLengthCheckDDL = {
    class LengthDDL(tag: Tag) extends Table[String](tag, "length_ddl") {
      def name = column[String]("name", O.Length(2))
      def *    = name
    }
    val lengthDDL = TableQuery[LengthDDL]

    val createStatement = lengthDDL.schema.createStatements.mkString(" ")
    createStatement.contains(
      "\"name\" VARCHAR(2) NOT NULL CHECK (length(\"name\") <= 2)"
    ) shouldBe true

    DBIO.seq(lengthDDL.schema.create)
  }

  def testLengthCheckCreateIfNotExistsDDL = {
    class LengthCreateIfNotExists(tag: Tag)
        extends Table[String](tag, "length_create_if_not_exists") {
      def name = column[String]("name", O.Length(2))
      def *    = name
    }
    val lengthCreateIfNotExists = TableQuery[LengthCreateIfNotExists]

    val createStatement =
      lengthCreateIfNotExists.schema.createIfNotExistsStatements.mkString(" ")
    createStatement.contains(
      "\"name\" VARCHAR(2) NOT NULL CHECK (length(\"name\") <= 2)"
    ) shouldBe true

    DBIO.seq(
      lengthCreateIfNotExists.schema.createIfNotExists,
      lengthCreateIfNotExists.schema.createIfNotExists
    )
  }

  def testLengthCheckDDLWithColumnOptions = {
    class LengthOptions(tag: Tag)
        extends Table[(Option[String], String, String, String)](
          tag,
          "length_options"
        ) {
      def nullableName =
        column[Option[String]]("nullable_name", O.Length(2))
      def uniqueName   = column[String]("unique_name", O.Length(2), O.Unique)
      def defaultName  =
        column[String]("default_name", O.Length(2), O.Default("x"))
      def fixedName    = column[String]("fixed_name", O.Length(2, varying = false))
      def *            =
        (nullableName, uniqueName, defaultName, fixedName)
    }
    val lengthOptions = TableQuery[LengthOptions]

    val createStatement = lengthOptions.schema.createStatements.mkString(" ")
    createStatement.contains(
      "\"nullable_name\" VARCHAR(2) CHECK (length(\"nullable_name\") <= 2)"
    ) shouldBe true
    createStatement.contains(
      "\"unique_name\" VARCHAR(2) NOT NULL UNIQUE CHECK (length(\"unique_name\") <= 2)"
    ) shouldBe true
    createStatement.contains(
      "\"default_name\" VARCHAR(2) DEFAULT 'x' NOT NULL CHECK (length(\"default_name\") <= 2)"
    ) shouldBe true
    createStatement.contains(
      "\"fixed_name\" CHAR(2) NOT NULL CHECK (length(\"fixed_name\") <= 2)"
    ) shouldBe true

    DBIO.seq(lengthOptions.schema.create)
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

  def testNonVaryingLengthCheck = {
    class NonVaryingLengthValues(tag: Tag)
        extends Table[(Int, String)](tag, "non_varying_length_values") {
      def id    = column[Int]("id", O.PrimaryKey)
      def value = column[String]("value", O.Length(2, varying = false))
      def *     = (id, value)
    }
    val values = TableQuery[NonVaryingLengthValues]

    DBIO.seq(
      values.schema.create,
      values += ((1, "ab")),
      (values += ((2, "abc"))).asTry.map(_.isFailure shouldBe true)
    )
  }

  def testLengthCheckWithUniqueColumn = {
    class UniqueLengthValues(tag: Tag)
        extends Table[(Int, String)](tag, "unique_length_values") {
      def id    = column[Int]("id", O.PrimaryKey)
      def value = column[String]("value", O.Length(2), O.Unique)
      def *     = (id, value)
    }
    val values = TableQuery[UniqueLengthValues]

    DBIO.seq(
      values.schema.create,
      values ++= Seq((1, "ab"), (2, "cd")),
      (values += ((3, "ab"))).asTry.map(_.isFailure shouldBe true),
      (values += ((4, "abc"))).asTry.map(_.isFailure shouldBe true)
    )
  }

  def testLengthCheckWithQuotedReservedName = {
    class ReservedNameLengthValues(tag: Tag)
        extends Table[(Int, String)](tag, "reserved_name_length_values") {
      def id       = column[Int]("id", O.PrimaryKey)
      def reserved = column[String]("select", O.Length(2))
      def *        = (id, reserved)
    }
    val values = TableQuery[ReservedNameLengthValues]

    val createStatement = values.schema.createStatements.mkString(" ")
    createStatement.contains(
      "\"select\" VARCHAR(2) NOT NULL CHECK (length(\"select\") <= 2)"
    ) shouldBe true

    DBIO.seq(
      values.schema.create,
      values += ((1, "if")),
      (values += ((2, "then"))).asTry.map(_.isFailure shouldBe true)
    )
  }

  def testLengthCheckWithDefault = {
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
      defaultValues.schema.create,
      defaultValues.map(_.id) += 1,
      defaultValues.result.map(_ shouldBe Seq((1, "x"))),
      oversizedDefaultValues.schema.create,
      (oversizedDefaultValues.map(_.id) += 1).asTry.map(
        _.isFailure shouldBe true
      )
    )
  }

  def testDuckDBCheckConstraintDDLAndEnforcement = {
    import duckdbslick.DuckDBProfile.api.*

    class CheckedValues(tag: Tag)
        extends Table[(Int, Int, Option[Int], Int, String, Boolean)](
          tag,
          "checked_values"
        ) {
      def id = column[Int]("id", O.PrimaryKey)
      def percentage = column[Int](
        "percentage",
        O.Check[Int]("percentage_min")(_ >= 0),
        O.Check[Int]("percentage_max")(_ <= 100)
      )
      def optionalMinimum = column[Option[Int]]("optional_minimum")
      def maximum         = column[Int](
        "maximum",
        O.Check[Int]("valid_optional_range")(max =>
          optionalMinimum <= max.?
        )
      )
      def code = column[String](
        "code",
        O.Length(10),
        O.Unique,
        O.Default("valid"),
        O.Check[String]("not_blank")(_.trim =!= "")
      )
      def enabled =
        column[Boolean](
          "enabled",
          O.Check[Boolean]("must_be_enabled")(enabled => enabled)
        )
      def * = (id, percentage, optionalMinimum, maximum, code, enabled)
    }
    val checkedValues = TableQuery[CheckedValues]

    val createStatement = checkedValues.schema.createStatements.mkString(" ")
    createStatement.contains(
      """"percentage" INTEGER NOT NULL CONSTRAINT "percentage_min" CHECK ("percentage" >= 0) CONSTRAINT "percentage_max" CHECK ("percentage" <= 100)"""
    ) shouldBe true
    createStatement.contains(
      """"maximum" INTEGER NOT NULL CONSTRAINT "valid_optional_range" CHECK ("optional_minimum" <= "maximum")"""
    ) shouldBe true
    createStatement.contains(
      """"code" VARCHAR(10) DEFAULT 'valid' NOT NULL UNIQUE CHECK (length("code") <= 10) CONSTRAINT "not_blank" CHECK ("""
    ) shouldBe true
    createStatement.contains(
      """"enabled" BOOLEAN NOT NULL CONSTRAINT "must_be_enabled" CHECK ("enabled")"""
    ) shouldBe true

    val repeatedCreateStatement =
      checkedValues.schema.createStatements.mkString(" ")
    repeatedCreateStatement shouldBe createStatement

    DBIO.seq(
      checkedValues.schema.create,
      checkedValues += ((1, 0, Some(0), 0, "a", true)),
      checkedValues += ((2, 100, None, 0, "b", true)),
      (checkedValues += ((3, -1, None, 0, "c", true))).asTry
        .map(_.isFailure shouldBe true),
      (checkedValues += ((4, 101, None, 0, "d", true))).asTry
        .map(_.isFailure shouldBe true),
      (checkedValues += ((5, 50, Some(2), 1, "e", true))).asTry
        .map(_.isFailure shouldBe true),
      (checkedValues += ((6, 50, None, 1, "f", false))).asTry
        .map(_.isFailure shouldBe true),
      checkedValues
        .filter(_.id === 1)
        .map(_.percentage)
        .update(101)
        .asTry
        .map(_.isFailure shouldBe true),
      checkedValues
        .insertOrUpdate((1, 101, Some(0), 0, "a", true))
        .asTry
        .map(_.isFailure shouldBe true)
    )
  }

  def testDuckDBCheckConstraintCrossColumnAndQuotedNames = {
    import duckdbslick.DuckDBProfile.api.*

    class Ranges(tag: Tag)
        extends Table[(Int, Int, Int)](tag, "check_ranges") {
      def id      = column[Int]("id")
      def minimum = column[Int]("minimum")
      def maximum = column[Int](
        "max\"imum",
        O.Check[Int]("valid\"range")(max => minimum <= max),
        O.Check[Int]("even_maximum")(_ % 2 === 0)
      )
      def * = (id, minimum, maximum)
    }
    val ranges = TableQuery[Ranges]

    val createStatement = ranges.schema.createIfNotExistsStatements.mkString(" ")
    createStatement.contains(
      """"max""imum" INTEGER NOT NULL CONSTRAINT "valid""range" CHECK ("minimum" <= "max""imum")"""
    ) shouldBe true
    createStatement.contains(
      """CONSTRAINT "even_maximum" CHECK (mod("max""imum",2) = 0)"""
    ) shouldBe true

    DBIO.seq(
      ranges.schema.createIfNotExists,
      ranges += ((1, 2, 4)),
      (ranges += ((2, 5, 4))).asTry.map(_.isFailure shouldBe true),
      (ranges += ((3, 2, 3))).asTry.map(_.isFailure shouldBe true)
    )
  }

  def testDuckDBCheckConstraintRejectsUnsafePredicates = {
    import duckdbslick.DuckDBProfile.api.*

    def errorFor[T <: Table[?]](query: TableQuery[T]): SlickException =
      Try(query.schema.createStatements.toSeq).failed.get
        .asInstanceOf[SlickException]

    class BoundCheck(tag: Tag) extends Table[Int](tag, "bound_check") {
      def value = column[Int](
        "value",
        O.Check[Int]("no_bind")(_ >= slick.lifted.LiteralColumn(0).bind)
      )
      def * = value
    }
    val boundError = errorFor(TableQuery[BoundCheck])
    boundError.getMessage.contains("no_bind") shouldBe true
    boundError.getMessage.contains("value") shouldBe true
    boundError.getMessage.contains("bind") shouldBe true

    class OmittedCheck(tag: Tag)
        extends Table[Int](tag, "omitted_check") {
      def omitted = column[Int]("omitted")
      def value   = column[Int](
        "value",
        O.Check[Int]("no_omitted")(current => omitted <= current)
      )
      def * = value
    }
    val omittedError = errorFor(TableQuery[OmittedCheck])
    omittedError.getMessage.contains("no_omitted") shouldBe true
    omittedError.getMessage.contains("value") shouldBe true
    omittedError.getMessage.contains("not included") shouldBe true

    class EmptyNameCheck(tag: Tag)
        extends Table[Int](tag, "empty_name_check") {
      def value = column[Int]("value", O.Check[Int]("  ")(_ >= 0))
      def *     = value
    }
    val emptyNameError = errorFor(TableQuery[EmptyNameCheck])
    emptyNameError.getMessage.contains("value") shouldBe true
    emptyNameError.getMessage.contains("non-empty") shouldBe true

    DBIO.successful(())
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
