package duckdbslick.types

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
    val escaped = new java.lang.StringBuilder("\"")
    var i = 0
    while (i < value.length) {
      val c = value.charAt(i)
      c match {
        case '"'  => escaped.append("\\\"")
        case '\\' => escaped.append("\\\\")
        case '\b' => escaped.append("\\b")
        case '\f' => escaped.append("\\f")
        case '\n' => escaped.append("\\n")
        case '\r' => escaped.append("\\r")
        case '\t' => escaped.append("\\t")
        case ch if ch < ' ' =>
          escaped.append("\\u")
          val hex = Integer.toHexString(ch.toInt)
          var padding = hex.length
          while (padding < 4) { escaped.append('0'); padding += 1 }
          escaped.append(hex)
        case ch if Character.isHighSurrogate(ch) =>
          require(i + 1 < value.length && Character.isLowSurrogate(value.charAt(i + 1)),
            "JSON string contains an unpaired high surrogate")
          escaped.append(ch)
          i += 1
          escaped.append(value.charAt(i))
        case ch if Character.isLowSurrogate(ch) =>
          throw new IllegalArgumentException("JSON string contains an unpaired low surrogate")
        case ch => escaped.append(ch)
      }
      i += 1
    }
    Json(escaped.append('"').toString)
  }
}

/** A DuckDB JSONPath or JSON Pointer expression. */
final case class JsonPath(value: String) {
  require(value != null, "JSON path cannot be null; use Option[JsonPath] for SQL NULL")
}
