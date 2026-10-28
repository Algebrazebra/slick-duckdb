package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import slick.SlickException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Try, Using}

class DuckDBInsertBehaviorTest {

  private def run[T](action: DBIO[T]): T =
    Using.resource(
      Database.forURL("jdbc:duckdb:", driver = "org.duckdb.DuckDBDriver")
    ) { db =>
      // One connection keeps the in-memory database shared by all statements.
      Await.result(db.run(action.withPinnedSession), 10.seconds)
    }

  @Test def testLengthCheckDDL(): Unit = {
    class LengthDDL(tag: Tag) extends Table[String](tag, "length_ddl") {
      def name = column[String]("name", O.Length(2))
      def *    = name
    }
    val lengthDDL = TableQuery[LengthDDL]

    val createStatement = lengthDDL.schema.createStatements.mkString(" ")
    assertTrue(
      createStatement.contains(
        "\"name\" VARCHAR(2) NOT NULL CHECK (length(\"name\") <= 2)"
      )
    )

    run(DBIO.seq(lengthDDL.schema.create))
  }

  @Test def testLengthCheckCreateIfNotExistsDDL(): Unit = {
    class LengthCreateIfNotExists(tag: Tag)
        extends Table[String](tag, "length_create_if_not_exists") {
      def name = column[String]("name", O.Length(2))
      def *    = name
    }
    val lengthCreateIfNotExists = TableQuery[LengthCreateIfNotExists]

    val createStatement =
      lengthCreateIfNotExists.schema.createIfNotExistsStatements.mkString(" ")
    assertTrue(
      createStatement.contains(
        "\"name\" VARCHAR(2) NOT NULL CHECK (length(\"name\") <= 2)"
      )
    )

    run(
      DBIO.seq(
        lengthCreateIfNotExists.schema.createIfNotExists,
        lengthCreateIfNotExists.schema.createIfNotExists
      )
    )
  }

  @Test def testLengthCheckDDLWithColumnOptions(): Unit = {
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
    assertTrue(
      createStatement.contains(
        "\"nullable_name\" VARCHAR(2) CHECK (length(\"nullable_name\") <= 2)"
      )
    )
    assertTrue(
      createStatement.contains(
        "\"unique_name\" VARCHAR(2) NOT NULL UNIQUE CHECK (length(\"unique_name\") <= 2)"
      )
    )
    assertTrue(
      createStatement.contains(
        "\"default_name\" VARCHAR(2) DEFAULT 'x' NOT NULL CHECK (length(\"default_name\") <= 2)"
      )
    )
    assertTrue(
      createStatement.contains(
        "\"fixed_name\" CHAR(2) NOT NULL CHECK (length(\"fixed_name\") <= 2)"
      )
    )

    run(DBIO.seq(lengthOptions.schema.create))
  }

  @Test def testLengthCheckBoundaryNullableAndUnicode(): Unit = {
    class LengthValues(tag: Tag)
        extends Table[(Int, Option[String])](tag, "length_values") {
      def id   = column[Int]("id", O.PrimaryKey)
      def name =
        column[Option[String]]("name", O.Length(2))
      def *    = (id, name)
    }
    val lengthValues = TableQuery[LengthValues]

    run(
      DBIO.seq(
        lengthValues.schema.create,
        lengthValues ++= Seq(
          (1, Some("")),
          (2, Some("ab")),
          (3, None),
          (4, Some("é界"))
        ),
        (lengthValues += ((5, Some("abc")))).asTry.map(result =>
          assertTrue(result.isFailure)
        ),
        lengthValues
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(
              Seq(
                (1, Some("")),
                (2, Some("ab")),
                (3, None),
                (4, Some("é界"))
              ),
              rows
            )
          )
      )
    )
  }

  @Test def testNonVaryingLengthCheck(): Unit = {
    class NonVaryingLengthValues(tag: Tag)
        extends Table[(Int, String)](tag, "non_varying_length_values") {
      def id    = column[Int]("id", O.PrimaryKey)
      def value = column[String]("value", O.Length(2, varying = false))
      def *     = (id, value)
    }
    val values = TableQuery[NonVaryingLengthValues]

    run(
      DBIO.seq(
        values.schema.create,
        values += ((1, "ab")),
        (values += ((2, "abc"))).asTry.map(result =>
          assertTrue(result.isFailure)
        )
      )
    )
  }

  @Test def testLengthCheckWithUniqueColumn(): Unit = {
    class UniqueLengthValues(tag: Tag)
        extends Table[(Int, String)](tag, "unique_length_values") {
      def id    = column[Int]("id", O.PrimaryKey)
      def value = column[String]("value", O.Length(2), O.Unique)
      def *     = (id, value)
    }
    val values = TableQuery[UniqueLengthValues]

    run(
      DBIO.seq(
        values.schema.create,
        values ++= Seq((1, "ab"), (2, "cd")),
        (values += ((3, "ab"))).asTry.map(result =>
          assertTrue(result.isFailure)
        ),
        (values += ((4, "abc"))).asTry.map(result =>
          assertTrue(result.isFailure)
        )
      )
    )
  }

  @Test def testLengthCheckWithQuotedReservedName(): Unit = {
    class ReservedNameLengthValues(tag: Tag)
        extends Table[(Int, String)](tag, "reserved_name_length_values") {
      def id       = column[Int]("id", O.PrimaryKey)
      def reserved = column[String]("select", O.Length(2))
      def *        = (id, reserved)
    }
    val values = TableQuery[ReservedNameLengthValues]

    val createStatement = values.schema.createStatements.mkString(" ")
    assertTrue(
      createStatement.contains(
        "\"select\" VARCHAR(2) NOT NULL CHECK (length(\"select\") <= 2)"
      )
    )

    run(
      DBIO.seq(
        values.schema.create,
        values += ((1, "if")),
        (values += ((2, "then"))).asTry.map(result =>
          assertTrue(result.isFailure)
        )
      )
    )
  }

  @Test def testLengthCheckWithDefault(): Unit = {
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

    run(
      DBIO.seq(
        defaultValues.schema.create,
        defaultValues.map(_.id) += 1,
        defaultValues.result.map(rows => assertEquals(Seq((1, "x")), rows)),
        oversizedDefaultValues.schema.create,
        (oversizedDefaultValues.map(_.id) += 1).asTry.map(result =>
          assertTrue(result.isFailure)
        )
      )
    )
  }

  @Test def testDuckDBCheckConstraintDDLAndEnforcement(): Unit = {
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
    assertTrue(
      createStatement.contains(
        """"percentage" INTEGER NOT NULL CONSTRAINT "percentage_min" CHECK ("percentage" >= 0) CONSTRAINT "percentage_max" CHECK ("percentage" <= 100)"""
      )
    )
    assertTrue(
      createStatement.contains(
        """"maximum" INTEGER NOT NULL CONSTRAINT "valid_optional_range" CHECK ("optional_minimum" <= "maximum")"""
      )
    )
    assertTrue(
      createStatement.contains(
        """"code" VARCHAR(10) DEFAULT 'valid' NOT NULL UNIQUE CHECK (length("code") <= 10) CONSTRAINT "not_blank" CHECK ("""
      )
    )
    assertTrue(
      createStatement.contains(
        """"enabled" BOOLEAN NOT NULL CONSTRAINT "must_be_enabled" CHECK ("enabled")"""
      )
    )

    val repeatedCreateStatement =
      checkedValues.schema.createStatements.mkString(" ")
    assertEquals(createStatement, repeatedCreateStatement)

    run(
      DBIO.seq(
        checkedValues.schema.create,
        checkedValues += (1, 0, Some(0), 0, "a", true),
        checkedValues += (2, 100, None, 0, "b", true),
        (checkedValues += (3, -1, None, 0, "c", true)).asTry
          .map(result => assertTrue(result.isFailure)),
        (checkedValues += (4, 101, None, 0, "d", true)).asTry
          .map(result => assertTrue(result.isFailure)),
        (checkedValues += (5, 50, Some(2), 1, "e", true)).asTry
          .map(result => assertTrue(result.isFailure)),
        (checkedValues += (6, 50, None, 1, "", true)).asTry
          .map(result => assertTrue(result.isFailure)),
        (checkedValues += (7, 50, None, 1, "f", false)).asTry
          .map(result => assertTrue(result.isFailure)),
        checkedValues
          .filter(_.id === 1)
          .map(_.percentage)
          .update(101)
          .asTry
          .map(result => assertTrue(result.isFailure)),
        checkedValues
          .insertOrUpdate((1, 101, Some(0), 0, "a", true))
          .asTry
          .map(result => assertTrue(result.isFailure))
      )
    )
  }

  @Test def testDuckDBCheckConstraintCrossColumnAndQuotedNames(): Unit = {
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
    assertTrue(
      createStatement.contains(
        """"max""imum" INTEGER NOT NULL CONSTRAINT "valid""range" CHECK ("minimum" <= "max""imum")"""
      )
    )
    assertTrue(
      createStatement.contains(
        """CONSTRAINT "even_maximum" CHECK (mod("max""imum",2) = 0)"""
      )
    )

    run(
      DBIO.seq(
        ranges.schema.createIfNotExists,
        ranges += (1, 2, 4),
        (ranges += (2, 5, 4)).asTry.map(result => assertTrue(result.isFailure)),
        (ranges += (3, 2, 3)).asTry.map(result => assertTrue(result.isFailure))
      )
    )
  }

  @Test def testDuckDBCheckConstraintRejectsUnsafePredicates(): Unit = {
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
    assertTrue(boundError.getMessage.contains("no_bind"))
    assertTrue(boundError.getMessage.contains("value"))
    assertTrue(boundError.getMessage.contains("bind"))

    class OmittedCheck(tag: Tag) extends Table[Int](tag, "omitted_check") {
      def omitted = column[Int]("omitted")
      def value   = column[Int](
        "value",
        O.Check[Int]("no_omitted")(current => omitted <= current)
      )
      def *       = value
    }
    val omittedError = errorFor(TableQuery[OmittedCheck])
    assertTrue(omittedError.getMessage.contains("no_omitted"))
    assertTrue(omittedError.getMessage.contains("value"))
    assertTrue(omittedError.getMessage.contains("not included"))

    class EmptyNameCheck(tag: Tag) extends Table[Int](tag, "empty_name_check") {
      def value = column[Int]("value", O.Check[Int]("  ")(_ >= 0))
      def *     = value
    }
    val emptyNameError = errorFor(TableQuery[EmptyNameCheck])
    assertTrue(emptyNameError.getMessage.contains("value"))
    assertTrue(emptyNameError.getMessage.contains("non-empty"))
  }

  @Test def testInsertOrUpdateExplicitAutoIncKeyChangesUniqueValue(): Unit = {
    class Users(tag: Tag)
        extends Table[(Int, String, String)](tag, "upsert_explicit_key") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[String]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]

    run(
      DBIO.seq(
        users.schema.create,
        users ++= Seq((0, "a", "first"), (0, "b", "second")),
        users
          .insertOrUpdate((1, "c", "changed"))
          .map(count => assertEquals(1, count)),
        users
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(Seq((1, "c", "changed"), (2, "b", "second")), rows)
          ),
        users
          .insertOrUpdate((10, "d", "inserted"))
          .map(count => assertEquals(1, count)),
        users
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(
              Seq(
                (1, "c", "changed"),
                (2, "b", "second"),
                (10, "d", "inserted")
              ),
              rows
            )
          )
      )
    )
  }

  @Test def testInsertOrUpdateExplicitAutoIncKeyRejectsOtherRowsUniqueValue()
      : Unit = {
    class Users(tag: Tag)
        extends Table[(Int, String, String)](tag, "upsert_unique_conflict") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[String]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]
    val original = Seq((1, "a", "first"), (2, "b", "second"))

    run(
      DBIO.seq(
        users.schema.create,
        users ++= original,
        users
          .insertOrUpdate((1, "b", "changed"))
          .asTry
          .map(result => assertTrue(result.isFailure)),
        users.sortBy(_.id).result.map(rows => assertEquals(original, rows)),
        users
          .insertOrUpdate((3, "b", "changed"))
          .asTry
          .map(result => assertTrue(result.isFailure)),
        users.sortBy(_.id).result.map(rows => assertEquals(original, rows))
      )
    )
  }

  @Test def testInsertOrUpdateGeneratedKeyCollisionPreservesExistingRow()
      : Unit = {
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

    run(
      DBIO.seq(
        users.schema.create,
        users.forceInsert((1, "a", "first")),
        users.insertOrUpdate((1, "c", "explicit")),
        users.insertOrUpdate((0, "c", "by unique value")),
        users
          .insertOrUpdate((0, "b", "new"))
          .asTry
          .map(result => assertTrue(result.isFailure)),
        users.result.map(rows =>
          assertEquals(Seq((1, "c", "by unique value")), rows)
        ),
        users.insertOrUpdate((0, "b", "new")),
        users
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(
              Seq((1, "c", "by unique value"), (2, "b", "new")),
              rows
            )
          )
      )
    )
  }

  @Test def testInsertOrUpdateAllExplicitAutoIncKeysAndGeneratedKeys(): Unit = {
    class Users(tag: Tag)
        extends Table[(Int, String, String)](tag, "upsert_mixed_keys") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def email = column[String]("email", O.Unique)
      def value = column[String]("value")
      def *     = (id, email, value)
    }
    val users = TableQuery[Users]

    run(
      DBIO.seq(
        users.schema.create,
        users ++= Seq((0, "a", "first"), (0, "b", "second")),
        users
          .insertOrUpdateAll(
            Seq((1, "c", "changed"), (0, "b", "generated")),
            slick.jdbc.RowsPerStatement.One
          )
          .map(_.foreach(count => assertEquals(2, count))),
        users
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(Seq((1, "c", "changed"), (2, "b", "generated")), rows)
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
          .map(_.foreach(count => assertEquals(3, count))),
        users.sortBy(_.id).result.map { rows =>
          assertEquals(
            Seq(
              (1, "d", "changed again"),
              (2, "b", "generated again")
            ),
            rows.take(2)
          )
          assertEquals(
            Seq(
              ("d", "changed again"),
              ("b", "generated again"),
              ("e", "new")
            ),
            rows.map(row => (row._2, row._3))
          )
          assertTrue(rows.last._1 > 2)
        },
        users
          .insertOrUpdateAll(
            Seq((1, "b", "wrong row")),
            slick.jdbc.RowsPerStatement.All
          )
          .asTry
          .map(result => assertTrue(result.isFailure)),
        users
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(
              Seq(
                (1, "d", "changed again"),
                (2, "b", "generated again")
              ),
              rows.take(2)
            )
          )
      )
    )
  }

  @Test def testInsertOrUpdateAllGeneratedKeysWithNullUniqueValues(): Unit = {
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

    run(
      DBIO.seq(
        users.schema.create,
        users.insertOrUpdateAll(
          Seq((0, None, "first"), (0, None, "second")),
          slick.jdbc.RowsPerStatement.All
        ),
        users
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(Seq((1, None, "first"), (2, None, "second")), rows)
          )
      )
    )
  }
}
