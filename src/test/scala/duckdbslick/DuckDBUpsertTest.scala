package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Using

class DuckDBUpsertTest {

  private def run[T](action: DBIO[T]): T =
    Using.resource(
      Database.forURL("jdbc:duckdb:", driver = "org.duckdb.DuckDBDriver")
    ) { db =>
      Await.result(db.run(action.withPinnedSession), 10.seconds)
    }

  private class Users(tag: Tag)
      extends Table[(Int, String, String)](tag, "upsert_users") {
    def id     = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def email  = column[String]("email", O.Unique)
    def handle = column[String]("handle", O.Unique)
    def *      = (id, email, handle)
  }

  @Test def testInsertOrUpdateWithIndependentUniqueColumns(): Unit = {
    val users = TableQuery[Users]

    run(
      DBIO.seq(
        users.schema.create,
        users
          .insertOrUpdate((0, "a", "b"))
          .map(count => assertEquals(1, count)),
        users.result.map(rows => assertEquals(Seq((1, "a", "b")), rows)),
        users
          .insertOrUpdate((1, "updated_email", "updated_handle"))
          .map(count => assertEquals(1, count)),
        users.result.map(rows =>
          assertEquals(Seq((1, "updated_email", "updated_handle")), rows)
        )
      )
    )
  }

  @Test def testInsertOrUpdateAllWithIndependentUniqueColumns(): Unit = {
    val users = TableQuery[Users]

    run(
      DBIO.seq(
        users.schema.create,
        users
          .insertOrUpdateAll(Seq((0, "a", "b"), (0, "c", "d")))
          .map(count => assertEquals(Some(2), count)),
        users
          .sortBy(_.id)
          .result
          .map(rows => assertEquals(Seq((1, "a", "b"), (2, "c", "d")), rows)),
        users
          .insertOrUpdateAll(
            Seq((1, "updated_email", "updated_handle"), (0, "e", "f"))
          )
          .map(count => assertEquals(Some(2), count)),
        users
          .sortBy(_.id)
          .result
          .map(rows =>
            assertEquals(
              Seq(
                (1, "updated_email", "updated_handle"),
                (2, "c", "d"),
                (3, "e", "f")
              ),
              rows
            )
          )
      )
    )
  }

  @Test def testInsertOrUpdateRejectsIndependentUniqueConflicts(): Unit =
    checkIndependentUniqueConflicts(multiRow = false)

  @Test def testInsertOrUpdateAllRejectsIndependentUniqueConflicts(): Unit =
    checkIndependentUniqueConflicts(multiRow = true)

  private def checkIndependentUniqueConflicts(multiRow: Boolean): Unit = {
    val users           = TableQuery[Users]
    val initial         = Seq((1, "a", "b"), (2, "c", "d"))
    val conflictingRows = Seq(
      (0, "a", "unused_handle"),
      (0, "unused_email", "b"),
      (99, "a", "unused_handle"),
      (99, "unused_email", "b"),
      (1, "c", "unused_handle"),
      (1, "unused_email", "d"),
      (0, "a", "d")
    )

    val checks: Seq[DBIO[Unit]] = conflictingRows.map { row =>
      val attempt: DBIO[Unit] =
        if (multiRow)
          users
            .insertOrUpdateAll(Seq((0, "valid_email", "valid_handle"), row))
            .map(_ => ())
        else users.insertOrUpdate(row).map(_ => ())

      DBIO.seq(
        attempt.asTry.map { result =>
          assertTrue(result.isFailure)
          assertTrue(
            result.failed.get.getMessage.contains("violates unique constraint")
          )
        },
        users.sortBy(_.id).result.map(rows => assertEquals(initial, rows))
      )
    }

    run(DBIO.seq(users.schema.create, users ++= initial, DBIO.sequence(checks)))
  }

  @Test def testGeneratedKeyCollisionPreservesIndependentUniqueColumns()
      : Unit = {
    val users    = TableQuery[Users]
    val original = Seq((1, "a", "b"))

    run(
      DBIO.seq(
        users.schema.create,
        users.forceInsert(original.head),
        users.insertOrUpdate((0, "c", "d")).asTry.map { result =>
          assertTrue(result.isFailure)
          assertTrue(
            result.failed.get.getMessage
              .contains("Generated primary key conflicts")
          )
        },
        users.result.map(rows => assertEquals(original, rows)),
        users.insertOrUpdate((0, "c", "d")),
        users
          .sortBy(_.id)
          .result
          .map(rows => assertEquals(Seq((1, "a", "b"), (2, "c", "d")), rows))
      )
    )
  }
}
