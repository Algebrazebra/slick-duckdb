package duckdbslick.api

import slick.jdbc.JdbcType

/** Opt-in evidence that a Scala type is mapped to SQL JSON. */
final class JsonTransformInput[J](val jdbcType: JdbcType[J]) {
  require(jdbcType.sqlTypeName(None).equalsIgnoreCase("JSON"),
    "json_transform input mapping must use SQL JSON")
}
