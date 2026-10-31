package duckdbslick.types

import java.time.{Instant, OffsetDateTime}

/** A nanosecond-precision instant stored in DuckDB's `TIMESTAMPTZ_NS` type.
  * DuckDB stores an instant, not the original UTC offset or time-zone name.
  */
final class TimestampTzNs private (private val instant: Instant) {
  def toInstant: Instant = instant

  override def equals(other: Any): Boolean = other match {
    case that: TimestampTzNs => instant == that.instant
    case _ => false
  }
  override def hashCode(): Int = instant.hashCode()
  override def toString: String = s"TimestampTzNs($instant)"
}

object TimestampTzNs {
  def from(value: Instant): TimestampTzNs = new TimestampTzNs(value)
  def from(value: OffsetDateTime): TimestampTzNs = from(value.toInstant)
}
