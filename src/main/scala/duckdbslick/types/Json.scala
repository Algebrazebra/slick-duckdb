package duckdbslick.types

import java.util.stream.Collectors.joining

/** JSON text, kept distinct from a SQL string. DuckDB validates the text when
  * the value is sent to the database.
  */
final case class Json(value: String) {
  require(value != null, "JSON text cannot be null; use Option[Json] for SQL NULL")
}

object Json {
  def number(value: Int): Json = Json(value.toString)
  def number(value: Long): Json = Json(value.toString)
  def number(value: BigInt): Json = Json(value.toString)
  def number(value: BigDecimal): Json = Json(value.toString)

  def boolean(value: Boolean): Json = Json(value.toString)
  val nullValue: Json = Json("null")

  def string(value: String): Json = {
    require(value != null, "JSON string cannot be null; use Option[Json] for SQL NULL")
    val escaped = value.codePoints().mapToObj[String] { cp =>
      cp match {
        case 0x22 => "\\\""
        case 0x5c => "\\\\"
        case 0x08 => "\\b"
        case 0x0c => "\\f"
        case 0x0a => "\\n"
        case 0x0d => "\\r"
        case 0x09 => "\\t"
        case cp if cp < 0x20 => f"\\u$cp%04x"
        case cp if cp >= 0xd800 && cp <= 0xdfff =>
          throw new IllegalArgumentException("JSON string contains an unpaired surrogate")
        case cp => new String(Character.toChars(cp))
      }
    }.collect(joining("", "\"", "\""))
    Json(escaped)
  }
}

/** A DuckDB JSONPath or JSON Pointer expression. */
final case class JsonPath(value: String) {
  require(value != null, "JSON path cannot be null; use Option[JsonPath] for SQL NULL")
}
