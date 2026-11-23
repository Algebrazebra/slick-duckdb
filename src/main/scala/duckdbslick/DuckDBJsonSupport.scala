package duckdbslick

import duckdbslick.types.{Json, JsonCodec}

import java.util.Objects.requireNonNull

/** Selects the Scala type used by a DuckDB profile's SQL JSON columns and functions. */
trait DuckDBJsonSupport[J] extends DuckDBProfileBase[J] {
  protected def jsonCodec: JsonCodec[J]

  protected final def createJsonJdbcType: BaseColumnType[J] = {
    val codec = jsonCodec
    api.MappedColumnType.base[J, Json](
      value => Json(codec.encode(value)),
      json => requireNonNull(codec.decode(json.value), "JSON decoder returned null")
    )(codec.classTag, columnTypes.jsonJdbcType)
  }
}
