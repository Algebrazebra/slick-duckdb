package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import duckdbslick.types.TimestampTzNs
import org.junit.Assume.assumeTrue
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import slick.SlickException

import java.time.{Instant, OffsetDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class DuckDBTimestampTzNsTest {
  private val supportsTimestampTzNs =
    DuckDBVersion.supportsTimestampTzNs(DuckDBProfile.getClass.getClassLoader)

  @Test def testTimestampTzNsDDL(): Unit = {
    class Events(tag: Tag) extends Table[TimestampTzNs](tag, "timestamp_tz_ns_ddl") {
      def happenedAt = column[TimestampTzNs]("happened_at")
      def * = happenedAt
    }

    if (supportsTimestampTzNs) {
      val statement = TableQuery[Events].schema.createStatements.mkString(" ")
      assertTrue(statement.contains("\"happened_at\" TIMESTAMPTZ_NS NOT NULL"))
    } else {
      val failure = Try(TableQuery[Events].schema.createStatements.toSeq).failed.get
      assertTrue(failure.isInstanceOf[SlickException])
      assertTrue(failure.getMessage.contains("requires DuckDB JDBC 2.0 or newer"))
    }
  }

  @Test def testTimestampTzNsRoundTrip(): Unit = {
    assumeTrue(supportsTimestampTzNs)

    class Events(tag: Tag)
        extends Table[(Int, Option[TimestampTzNs])](tag, "timestamp_tz_ns_values") {
      def id = column[Int]("id", O.PrimaryKey)
      def happenedAt = column[Option[TimestampTzNs]]("happened_at")
      def * = (id, happenedAt)
    }
    val events = TableQuery[Events]
    val beforeEpoch = TimestampTzNs(
      OffsetDateTime.parse("1969-12-31T23:59:59.123456789+00:00")
    )
    val withOffset = TimestampTzNs(
      OffsetDateTime.parse("2026-10-31T12:34:56.987654321+05:45")
    )
    val rows = Seq(
      (1, Some(beforeEpoch)),
      (2, Some(withOffset)),
      (3, None),
      (4, Some(TimestampTzNs(Instant.parse("1970-01-01T00:00:00.000000001Z"))))
    )

    val (stored, matched) = run(for {
      _      <- events.schema.create
      _      <- events ++= rows
      _      <- sqlu"SET TimeZone = 'Asia/Kathmandu'"
      stored <- events.sortBy(_.id).result
      matched <- events.filter(_.happenedAt === Option(beforeEpoch)).result
    } yield (stored, matched))

    assertEquals(rows, stored)
    assertEquals(Seq(rows.head), matched)
  }
}
