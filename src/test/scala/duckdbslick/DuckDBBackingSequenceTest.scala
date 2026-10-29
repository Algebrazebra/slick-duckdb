package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.concurrent.ExecutionContext.Implicits.global

class DuckDBBackingSequenceTest {

  @Test def testQuotedTableAndColumnNames(): Unit = {
    class Quoted(tag: Tag)
        extends Table[(Int, String)](tag, "odd-table's\"") {
      def id    = column[Int]("i'd\"", O.PrimaryKey, O.AutoInc)
      def value = column[String]("value")
      def *     = (id, value)
    }
    val rows = TableQuery[Quoted]

    val actual = run(for {
      _      <- rows.schema.create
      _      <- rows.map(_.value) += "inserted"
      _      <- rows.insertOrUpdate((0, "upserted"))
      before <- rows.sortBy(_.id).result
      _      <- rows.schema.drop
      _      <- rows.schema.create
      _      <- rows.map(_.value) += "recreated"
      after  <- rows.result
    } yield (before, after))

    assertEquals(Seq((1, "inserted"), (2, "upserted")), actual._1)
    assertEquals(Seq((1, "recreated")), actual._2)
  }

  @Test def testSameTableNameInDifferentSchemas(): Unit = {
    class AEntries(tag: Tag)
        extends Table[(Int, String)](tag, Some("a"), "entries") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def value = column[String]("value")
      def *     = (id, value)
    }
    class BEntries(tag: Tag)
        extends Table[(Int, String)](tag, Some("b"), "entries") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def value = column[String]("value")
      def *     = (id, value)
    }
    val a = TableQuery[AEntries]
    val b = TableQuery[BEntries]

    val actual = run(for {
      _      <- sqlu"create schema a"
      _      <- sqlu"create schema b"
      _      <- a.schema.create
      _      <- b.schema.create
      _      <- a.map(_.value) += "a first"
      _      <- b.map(_.value) += "b first"
      _      <- a.insertOrUpdate((0, "a second"))
      _      <- b.insertOrUpdate((0, "b second"))
      aRows  <- a.sortBy(_.id).result
      bRows  <- b.sortBy(_.id).result
      _      <- a.schema.drop
      _      <- b.schema.drop
    } yield (aRows, bRows))

    assertEquals(Seq((1, "a first"), (2, "a second")), actual._1)
    assertEquals(Seq((1, "b first"), (2, "b second")), actual._2)
  }

  @Test def testApostrophesInQualifiedSequenceName(): Unit = {
    class Quoted(tag: Tag)
        extends Table[(Int, String)](tag, Some("team's"), "odd-table's") {
      def id    = column[Int]("i'd", O.PrimaryKey, O.AutoInc)
      def value = column[String]("value")
      def *     = (id, value)
    }
    val rows = TableQuery[Quoted]

    val createStatements = rows.schema.createStatements.toSeq
    assertTrue(
      createStatements.contains(
        """create sequence "team's"."odd-table's_i'd_seq""""
      )
    )
    assertTrue(
      createStatements.exists(_.contains(
        """nextval('"team''s"."odd-table''s_i''d_seq"')"""
      ))
    )

    val actual = run(for {
      _      <- sqlu"""create schema "team's" """
      _      <- rows.schema.create
      _      <- rows.map(_.value) += "inserted"
      _      <- rows.insertOrUpdate((0, "upserted"))
      result <- rows.sortBy(_.id).result
    } yield result)

    assertEquals(Seq((1, "inserted"), (2, "upserted")), actual)
  }

  @Test def testEmbeddedQuoteInSchemaName(): Unit = {
    class QuotedSchema(tag: Tag)
        extends Table[(Int, String)](tag, Some("a\"b"), "entries") {
      def id    = column[Int]("id", O.PrimaryKey, O.AutoInc)
      def value = column[String]("value")
      def *     = (id, value)
    }
    val rows = TableQuery[QuotedSchema]

    val actual = run(for {
      _      <- sqlu"""create schema "a""b" """
      _      <- rows.schema.create
      _      <- rows.map(_.value) += "inserted"
      _      <- rows.insertOrUpdate((0, "upserted"))
      before <- rows.sortBy(_.id).result
      _      <- rows.schema.drop
      _      <- rows.schema.create
      _      <- rows.map(_.value) += "recreated"
      after  <- rows.result
    } yield (before, after))

    assertEquals(Seq((1, "inserted"), (2, "upserted")), actual._1)
    assertEquals(Seq((1, "recreated")), actual._2)
  }
}
