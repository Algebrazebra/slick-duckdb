package components

import slick.ast.FieldSymbol
import slick.jdbc.JdbcProfile

import java.sql.{Blob, PreparedStatement, ResultSet, Time}
import java.util.UUID
import javax.sql.rowset.serial.SerialBlob

trait DuckDBJdbcTypesComponent {
  self: JdbcProfile =>

  /** DuckDB-specific type mappings.
    *
    * This class overrides the default JDBCTypes mainly to implement the correct
    * literal handling where it's necessary (e.g., mostly time-related types).
    * Additionally, this class provides an implementation for the `BlobJdbcType`
    * using serialized `ByteArray`s, because the DuckDB JDBC driver doesn't
    * implement all the required functionality (i.e.,
    * `DuckDBPreparedStatement.setBlob`).
    */
  class DuckDBJdbcTypes extends JdbcTypes {

    override val blobJdbcType: BlobJdbcType = new DuckDBBlobJdbcType
    private class DuckDBBlobJdbcType extends super.BlobJdbcType {
      // The DuckDB JDBC driver doesn't support Blobs because the
      // `DuckDBPreparedStatement.setBlob` is not implemented.
      // The workaround is to treat Blobs as serialized ByteArrays instead.
      override def sqlType: Int                                            = java.sql.Types.BINARY
      override def sqlTypeName(sym: Option[FieldSymbol]): String           = "BLOB"
      override def setValue(v: Blob, p: PreparedStatement, idx: Int): Unit =
        p.setBytes(idx, v.getBytes(1, v.length.toInt))
      override def getValue(rs: ResultSet, idx: Int): Blob                 = {
        val bytes = rs.getBytes(idx)
        new SerialBlob(bytes)
      }
      override def updateValue(v: Blob, rs: ResultSet, idx: Int): Unit     =
        rs.updateBytes(idx, v.getBytes(1, v.length.toInt))
      override val hasLiteralForm                                          = false
    }

    override val timeJdbcType: TimeJdbcType = new DuckDBTimeJdbcType
    private class DuckDBTimeJdbcType extends super.TimeJdbcType {
      override def valueToSQLLiteral(value: Time): String =
        "'" + value.toString + "'"
      override def hasLiteralForm: Boolean                = true
    }

    override val timestampJdbcType: TimestampJdbcType =
      new DuckDBTimestampJdbcType
    private class DuckDBTimestampJdbcType extends super.TimestampJdbcType {
      override def valueToSQLLiteral(value: java.sql.Timestamp): String =
        "'" + value.toString + "'"
      override def hasLiteralForm: Boolean                              = true
    }

    override val dateJdbcType: DateJdbcType = new DuckDBDateJdbcType
    private class DuckDBDateJdbcType extends super.DateJdbcType {
      override def valueToSQLLiteral(value: java.sql.Date): String =
        "'" + value.toString + "'"
      override def hasLiteralForm: Boolean                         = true
    }

    override val uuidJdbcType: UUIDJdbcType = new DuckDBUUIDJdbcType
    private class DuckDBUUIDJdbcType extends super.UUIDJdbcType {
      override def sqlTypeName(sym: Option[FieldSymbol]): String           = "UUID"
      override def setValue(v: UUID, p: PreparedStatement, idx: Int): Unit =
        p.setString(idx, if (v == null) null else v.toString)
      override def getValue(r: ResultSet, idx: Int): UUID                  = {
        val s = r.getString(idx)
        if (s eq null) null else UUID.fromString(s)
      }
      override def updateValue(v: UUID, r: ResultSet, idx: Int): Unit      =
        r.updateString(idx, if (v == null) null else v.toString)
      override def valueToSQLLiteral(value: UUID): String                  =
        if (value == null) "NULL" else "'" + value.toString + "'"
      override def hasLiteralForm: Boolean                                 = true
    }

  }

}
