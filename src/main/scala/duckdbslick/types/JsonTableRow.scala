package duckdbslick.types

import slick.jdbc.GetResult

import java.sql.ResultSet

/** One row from `json_each` or `json_tree` with the explicit projection
  * `key, value, type, atom, id, parent, fullkey, path, json, root, rowid`.
  * `id` and `rowId` are local to a table-function invocation, not database keys.
  */
final case class JsonTableRow[J](
    key: Option[String],
    value: J,
    jsonType: String,
    atom: Option[J],
    id: BigInt,
    parent: Option[BigInt],
    fullKey: String,
    path: String,
    inputJson: J,
    root: Option[String],
    rowId: Long
)

object JsonTableRow {
  /** Build a decoder for another SQL JSON representation without changing the
    * traversal metadata contract. `readJson` must preserve JSON null as a value.
    */
  def getResult[J](readJson: (ResultSet, Int) => J): GetResult[JsonTableRow[J]] =
    GetResult { r =>
      def jsonValue(): Option[J] = {
        val value = readJson(r.rs, r.currentPos + 1)
        val sqlNull = r.wasNull()
        r.skip
        if (sqlNull) None else Some(value)
      }
      def requiredJson(): J = jsonValue().getOrElse(
        throw new java.sql.SQLException("json table function returned SQL NULL in a required JSON column")
      )
      def unsigned(): BigInt = BigInt(r.nextString())
      def unsignedOption(): Option[BigInt] = r.nextStringOption().map(BigInt(_))

      JsonTableRow(
        r.nextStringOption(),
        requiredJson(),
        r.nextString(),
        jsonValue(),
        unsigned(),
        unsignedOption(),
        r.nextString(),
        r.nextString(),
        requiredJson(),
        r.nextStringOption(),
        r.nextLong()
      )
    }
}
