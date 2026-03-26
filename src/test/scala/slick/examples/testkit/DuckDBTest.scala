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

  object additionalCapabilities {
    val jdbcMetaGetUDTs     = new Capability("test.jdbcMetaGetUDTs")
    val jdbcMetaGetTypeInfo = new Capability("test.jdbcMetaGetTypeInfo")
  }
  def tdb: ExternalJdbcTestDB = new ExternalJdbcTestDB("duckdb") {
    val profile: DuckDBProfile.type = DuckDBProfile

    private val unsupportedCapabilities = Set(
      TestDB.capabilities.jdbcMetaGetClientInfoProperties,
      TestDB.capabilities.transactionIsolation,
      additionalCapabilities.jdbcMetaGetUDTs,
    )

    override def capabilities: Set[Capability] =
      super.capabilities -- unsupportedCapabilities
  }
}
