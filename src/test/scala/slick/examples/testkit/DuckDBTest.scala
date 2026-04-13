package slick.examples.testkit

import com.typesafe.slick.testkit.tests.{
  ForeignKeyTest,
  InsertTest,
  JdbcMetaTest,
  PlainSQLTest
}
import com.typesafe.slick.testkit.util.*
import duckdbslick.DuckDBProfile
import org.junit.runner.RunWith
import slick.basic.Capability
import scala.util.Using

@RunWith(classOf[Testkit])
class DuckDBTest extends ProfileTest(DuckDBTest.tdb) {

  private val disabled: Set[Class[?]] = Set(
    // To disable test, add:
    // classOf[com.typesafe.slick.testkit.tests.<class>]
  )

  override lazy val tests: Seq[Class[? <: AsyncTest[? >: Null <: TestDB]]] =
    super.tests
      .filterNot(disabled.contains)
      .map {
        case c if c == classOf[JdbcMetaTest]   => classOf[DuckDBJdbcMetaTest]
        case c if c == classOf[PlainSQLTest]   => classOf[DuckDBPlainSQLTest]
        case c if c == classOf[ForeignKeyTest] => classOf[DuckDBForeignKeyTest]
        case c if c == classOf[InsertTest]     => classOf[DuckDBInsertTest]
        case c                                 => c
      }

}

object DuckDBTest {

  /** Capabilities that are not natively available in Slick but are required for
    * describing the capabilities of the DuckDB JDBC driver.
    */
  object additionalCapabilities {
    val jdbcMetaGetUDTs     = new Capability("test.jdbcMetaGetUDTs")
    val jdbcMetaGetTypeInfo = new Capability("test.jdbcMetaGetTypeInfo")
  }

  def tdb: ExternalJdbcTestDB = new ExternalJdbcTestDB("duckdb") {
    val profile: DuckDBProfile.type = DuckDBProfile

    private val unsupportedCapabilities = Set(
      TestDB.capabilities.jdbcMetaGetClientInfoProperties,
      TestDB.capabilities.transactionIsolation,

      // Since this capability is not natively available in Slick,
      // adding it to the set of unsupported capabilities is purely performative.
      additionalCapabilities.jdbcMetaGetUDTs
    )

    override def capabilities: Set[Capability] = {
      val base = super.capabilities -- unsupportedCapabilities
      if (duckDBVersionAtLeast(1, 4, 0))
        base + additionalCapabilities.jdbcMetaGetTypeInfo
      else base
    }
  }

  def duckDBVersionAtLeast(major: Int, minor: Int, patch: Int): Boolean = {
    val version                       = getDuckDBVersion
    val Array(vMajor, vMinor, vPatch) =
      version.stripPrefix("v").split("\\.").map(_.toInt)
    Ordering[(Int, Int, Int)]
      .gteq((vMajor, vMinor, vPatch), (major, minor, patch))
  }

  /** Reads the DuckDB database version.
    *
    * Technically speaking, the DuckDB version is independent of the JDBC driver
    * version; they aren't necessarily the same. For our purposes, they are
    * equivalent since the JDBC driver by default creates the DuckDB database in
    * the same version. This hacky workaround is necessary because the JDBC
    * driver doesn't expose the DuckDB version correctly. That's why we use the
    * DuckDB version as the JDBC driver version. And because attempts like
    * classOf[org.duckdb.DuckDBDriver].getPackage.getImplementationVersion
    * failed, I had no choice but to instantiate a DuckDB connection.
    */
  def getDuckDBVersion: String = {
    Using.resource(tdb.createDB()) { db =>
      Using.resource(db.createSession()) { session =>
        session.conn.getMetaData.getDatabaseProductVersion.stripPrefix("v")
      }
    }
  }
}
