package slick.examples.testkit

import com.typesafe.slick.testkit.util.{AsyncTest, JdbcTestDB}
import slick.SlickException

import scala.util.Try

class DuckDBInsertBehaviorTest extends AsyncTest[JdbcTestDB] {

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
      def id              = column[Int]("id", O.PrimaryKey)
      def percentage      = column[Int](
        "percentage",
        O.Check[Int]("percentage_min")(_ >= 0),
        O.Check[Int]("percentage_max")(_ <= 100)
      )
      def optionalMinimum = column[Option[Int]]("optional_minimum")
      def maximum         = column[Int](
        "maximum",
        O.Check[Int]("valid_optional_range")(max => optionalMinimum <= max.?)
      )
      def code            = column[String](
        "code",
        O.Length(10),
        O.Unique,
        O.Default("valid"),
        O.Check[String]("not_blank")(_.trim =!= "")
      )
      def enabled         =
        column[Boolean](
          "enabled",
          O.Check[Boolean]("must_be_enabled")(identity)
        )
      def *               = (id, percentage, optionalMinimum, maximum, code, enabled)
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
      checkedValues += (1, 0, Some(0), 0, "a", true),
      checkedValues += (2, 100, None, 0, "b", true),
      (checkedValues += (3, -1, None, 0, "c", true)).asTry
        .map(_.isFailure shouldBe true),
      (checkedValues += (4, 101, None, 0, "d", true)).asTry
        .map(_.isFailure shouldBe true),
      (checkedValues += (5, 50, Some(2), 1, "e", true)).asTry
        .map(_.isFailure shouldBe true),
      (checkedValues += (6, 50, None, 1, "", true)).asTry
        .map(_.isFailure shouldBe true),
      (checkedValues += (7, 50, None, 1, "f", false)).asTry
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

    class Ranges(tag: Tag) extends Table[(Int, Int, Int)](tag, "check_ranges") {
      def id      = column[Int]("id")
      def minimum = column[Int]("minimum")
      def maximum = column[Int](
        "max\"imum",
        O.Check[Int]("valid\"range")(max => minimum <= max),
        O.Check[Int]("even_maximum")(_ % 2 === 0)
      )
      def *       = (id, minimum, maximum)
    }
    val ranges = TableQuery[Ranges]

    val createStatement =
      ranges.schema.createIfNotExistsStatements.mkString(" ")
    createStatement.contains(
      """"max""imum" INTEGER NOT NULL CONSTRAINT "valid""range" CHECK ("minimum" <= "max""imum")"""
    ) shouldBe true
    createStatement.contains(
      """CONSTRAINT "even_maximum" CHECK (mod("max""imum",2) = 0)"""
    ) shouldBe true

    DBIO.seq(
      ranges.schema.createIfNotExists,
      ranges += (1, 2, 4),
      (ranges += (2, 5, 4)).asTry.map(_.isFailure shouldBe true),
      (ranges += (3, 2, 3)).asTry.map(_.isFailure shouldBe true)
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
      def *     = value
    }
    val boundError = errorFor(TableQuery[BoundCheck])
    boundError.getMessage.contains("no_bind") shouldBe true
    boundError.getMessage.contains("value") shouldBe true
    boundError.getMessage.contains("bind") shouldBe true

    class OmittedCheck(tag: Tag) extends Table[Int](tag, "omitted_check") {
      def omitted = column[Int]("omitted")
      def value   = column[Int](
        "value",
        O.Check[Int]("no_omitted")(current => omitted <= current)
      )
      def *       = value
    }
    val omittedError = errorFor(TableQuery[OmittedCheck])
    omittedError.getMessage.contains("no_omitted") shouldBe true
    omittedError.getMessage.contains("value") shouldBe true
    omittedError.getMessage.contains("not included") shouldBe true

    class EmptyNameCheck(tag: Tag) extends Table[Int](tag, "empty_name_check") {
      def value = column[Int]("value", O.Check[Int]("  ")(_ >= 0))
      def *     = value
    }
    val emptyNameError = errorFor(TableQuery[EmptyNameCheck])
    emptyNameError.getMessage.contains("value") shouldBe true
    emptyNameError.getMessage.contains("non-empty") shouldBe true

    DBIO.successful(())
  }

  def testInsertOrUpdateExplicitAutoIncKeyChangesUniqueValue = {
    class Users(tag: Tag)
        extends Table[(Int, String, String)](tag, "upsert_explicit_key") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[String]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]

    DBIO.seq(
      users.schema.create,
      users ++= Seq((0, "a", "first"), (0, "b", "second")),
      users.insertOrUpdate((1, "c", "changed")).map(_ shouldBe 1),
      users
        .sortBy(_.id)
        .result
        .map(
          _ shouldBe Seq((1, "c", "changed"), (2, "b", "second"))
        ),
      users.insertOrUpdate((10, "d", "inserted")).map(_ shouldBe 1),
      users
        .sortBy(_.id)
        .result
        .map(
          _ shouldBe Seq(
            (1, "c", "changed"),
            (2, "b", "second"),
            (10, "d", "inserted")
          )
        )
    )
  }

  def testInsertOrUpdateExplicitAutoIncKeyRejectsOtherRowsUniqueValue = {
    class Users(tag: Tag)
        extends Table[(Int, String, String)](tag, "upsert_unique_conflict") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[String]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]
    val original = Seq((1, "a", "first"), (2, "b", "second"))

    DBIO.seq(
      users.schema.create,
      users ++= original,
      users
        .insertOrUpdate((1, "b", "changed"))
        .asTry
        .map(
          _.isFailure shouldBe true
        ),
      users.sortBy(_.id).result.map(_ shouldBe original),
      users
        .insertOrUpdate((3, "b", "changed"))
        .asTry
        .map(
          _.isFailure shouldBe true
        ),
      users.sortBy(_.id).result.map(_ shouldBe original)
    )
  }

  def testInsertOrUpdateGeneratedKeyCollisionPreservesExistingRow = {
    class Users(tag: Tag)
        extends Table[(Int, String, String)](
          tag,
          "upsert_generated_collision"
        ) {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[String]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]

    DBIO.seq(
      users.schema.create,
      users.forceInsert((1, "a", "first")),
      users.insertOrUpdate((1, "c", "explicit")),
      users.insertOrUpdate((0, "c", "by unique value")),
      users
        .insertOrUpdate((0, "b", "new"))
        .asTry
        .map(
          _.isFailure shouldBe true
        ),
      users.result.map(_ shouldBe Seq((1, "c", "by unique value"))),
      users.insertOrUpdate((0, "b", "new")),
      users
        .sortBy(_.id)
        .result
        .map(
          _ shouldBe Seq((1, "c", "by unique value"), (2, "b", "new"))
        )
    )
  }

  def testInsertOrUpdateAllExplicitAutoIncKeysAndGeneratedKeys = {
    import duckdbslick.DuckDBProfile.api.*

    class Users(tag: Tag)
        extends Table[(Int, String, String)](tag, "upsert_mixed_keys") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[String]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]

    DBIO.seq(
      users.schema.create,
      users ++= Seq((0, "a", "first"), (0, "b", "second")),
      users
        .insertOrUpdateAll(
          Seq((1, "c", "changed"), (0, "b", "generated")),
          slick.jdbc.RowsPerStatement.One
        )
        .map(_.foreach(_ shouldBe 2)),
      users
        .sortBy(_.id)
        .result
        .map(
          _ shouldBe Seq((1, "c", "changed"), (2, "b", "generated"))
        ),
      users
        .insertOrUpdateAll(
          Seq(
            (1, "d", "changed again"),
            (0, "b", "generated again"),
            (0, "e", "new")
          ),
          slick.jdbc.RowsPerStatement.All
        )
        .map(_.foreach(_ shouldBe 3)),
      users.sortBy(_.id).result.map { rows =>
        rows.take(2) shouldBe Seq(
          (1, "d", "changed again"),
          (2, "b", "generated again")
        )
        rows.map(row => (row._2, row._3)) shouldBe Seq(
          ("d", "changed again"),
          ("b", "generated again"),
          ("e", "new")
        )
        (rows.last._1 > 2) shouldBe true
      },
      users
        .insertOrUpdateAll(
          Seq((1, "b", "wrong row")),
          slick.jdbc.RowsPerStatement.All
        )
        .asTry
        .map(_.isFailure shouldBe true),
      users
        .sortBy(_.id)
        .result
        .map(
          _.take(2) shouldBe Seq(
            (1, "d", "changed again"),
            (2, "b", "generated again")
          )
        )
    )
  }

  def testInsertOrUpdateAllGeneratedKeysWithNullUniqueValues = {
    import duckdbslick.DuckDBProfile.api.*

    class Users(tag: Tag)
        extends Table[(Int, Option[String], String)](
          tag,
          "upsert_null_unique"
        ) {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[Option[String]]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]

    DBIO.seq(
      users.schema.create,
      users.insertOrUpdateAll(
        Seq((0, None, "first"), (0, None, "second")),
        slick.jdbc.RowsPerStatement.All
      ),
      users
        .sortBy(_.id)
        .result
        .map(
          _ shouldBe Seq((1, None, "first"), (2, None, "second"))
        )
    )
  }
}
