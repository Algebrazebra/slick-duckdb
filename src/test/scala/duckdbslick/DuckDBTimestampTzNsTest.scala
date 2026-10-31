package duckdbslick

import duckdbslick.DuckDBProfile.api.{TimestampTzNs => _, *}
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assume.assumeTrue
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import slick.SlickException

import java.sql.Timestamp
import java.time.{Instant, OffsetDateTime}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class DuckDBTimestampTzNsTest {
  private val supportsTimestampTzNs =
    sys.props.getOrElse("duckdb.version", "2.0.0.0")
      .takeWhile(_ != '.').toInt >= 2

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
      assertTrue(failure.getMessage.contains("requires DuckDB JDBC 2.0"))
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
    val beforeEpoch = TimestampTzNs.from(
      OffsetDateTime.parse("1969-12-31T23:59:59.123456789+00:00")
    )
    val withOffset = TimestampTzNs.from(
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

  @Test def testExistingTimestampMapping(): Unit = {
    class LegacyEvents(tag: Tag)
        extends Table[(Int, Option[Timestamp])](tag, "legacy_timestamp_values") {
      def id = column[Int]("id", O.PrimaryKey)
      def happenedAt = column[Option[Timestamp]]("happened_at")
      def * = (id, happenedAt)
    }
    val events = TableQuery[LegacyEvents]
    val rows = Seq(
      (1, Some(Timestamp.valueOf("2020-01-02 03:04:05.123456"))),
      (2, None)
    )

    val stored = run(for {
      _      <- events.schema.create
      _      <- events ++= rows
      stored <- events.sortBy(_.id).result
    } yield stored)

    assertEquals(rows, stored)
  }
}
