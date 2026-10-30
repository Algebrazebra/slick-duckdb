package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import slick.SlickException
import slick.jdbc.JdbcCapabilities

import java.sql.SQLException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class DuckDBReturningTest {

  private def generatedKeysSupported: Boolean =
    run(SimpleDBIO(_.connection.getMetaData.getDatabaseProductVersion))
      .stripPrefix("v")
      .takeWhile(_ != '.')
      .toInt >= 2

  @Test def testCapabilitiesFollowDriverVersion(): Unit = {
    val supported = generatedKeysSupported
    val returningCapabilities = Seq(
      JdbcCapabilities.returnInsertKey,
      JdbcCapabilities.returnMultipleInsertKey,
      JdbcCapabilities.returnInsertOther
    )
    returningCapabilities.foreach(capability =>
      assertEquals(
        supported,
        DuckDBProfile.capabilities.contains(capability)
      )
    )
  }

  @Test def testSingleGeneratedKey(): Unit = {
    class Entries(tag: Tag) extends Table[(Int, String)](tag, "returning_entries") {
      def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def name = column[String]("name")
      def * = (id, name)
    }
    val entries = TableQuery[Entries]

    val action = for {
      _ <- entries.schema.create
      id <- (entries.map(_.name) returning entries.map(_.id)) += "first"
      rows <- entries.result
    } yield {
      assertEquals(1, id)
      assertEquals(Seq((1, "first")), rows)
    }

    if (generatedKeysSupported) run(action)
    else
      assertTrue(
        Try(run(action)).failed.toOption.exists(_.isInstanceOf[SlickException])
      )
  }

  @Test def testReturningFromAnotherTableIsRejected(): Unit = {
    if (!generatedKeysSupported) return

    class Entries(tag: Tag) extends Table[(Int, String)](tag, "returning_source") {
      def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def name = column[String]("name")
      def * = (id, name)
    }
    class Other(tag: Tag) extends Table[(Int, String)](tag, "returning_other") {
      def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def name = column[String]("name")
      def * = (id, name)
    }
    val entries = TableQuery[Entries]
    val other = TableQuery[Other]
    val action = for {
      _ <- entries.schema.create
      _ <- other.schema.create
      _ <- (entries.map(_.name) returning other.map(_.id)) += "first"
    } yield ()

    assertTrue(
      Try(run(action)).failed.toOption.exists(_.isInstanceOf[SlickException])
    )
  }

  @Test def testReturningUpsertRejectsUniqueConflict(): Unit = {
    if (!generatedKeysSupported) return

    class Entries(tag: Tag) extends Table[(Int, String, Int)](tag, "returning_upsert") {
      def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def name = column[String]("name", O.Unique)
      def value = column[Int]("value")
      def * = (id, name, value)
    }
    val entries = TableQuery[Entries]
    val insert = entries returning entries.map(_.id)

    run(for {
      _ <- entries.schema.create
      first <- insert.insertOrUpdate((0, "same", 1))
      second <- insert.insertOrUpdate((0, "same", 2)).asTry
      rows <- entries.result
    } yield {
      assertEquals(Some(1), first)
      assertTrue(second.failed.toOption.exists(_.isInstanceOf[SQLException]))
      assertEquals(Seq((1, "same", 1)), rows)
    })
  }
}
