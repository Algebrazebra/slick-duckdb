package duckdbslick.types

import java.time.{Instant, OffsetDateTime}

/** A nanosecond-precision instant stored in DuckDB's `TIMESTAMPTZ_NS` type.
  * DuckDB stores an instant, not the original UTC offset or time-zone name.
  */
final case class TimestampTzNs(instant: Instant)

object TimestampTzNs {
  def apply(value: OffsetDateTime): TimestampTzNs =
    new TimestampTzNs(value.toInstant)
}
