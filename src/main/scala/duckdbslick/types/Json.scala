package duckdbslick.types

/** JSON text, kept distinct from a SQL string. DuckDB validates the text when
  * the value is sent to the database.
  */
final case class Json(value: String) {
  require(value != null, "JSON text cannot be null; use Option[Json] for SQL NULL")
}

/** A DuckDB JSONPath or JSON Pointer expression. */
final case class JsonPath(value: String) {
  require(value != null, "JSON path cannot be null; use Option[JsonPath] for SQL NULL")
}
