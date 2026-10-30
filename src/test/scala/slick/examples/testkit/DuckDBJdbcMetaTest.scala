package slick.examples.testkit

import com.typesafe.slick.testkit.tests.JdbcMetaTest
import slick.examples.testkit.DuckDBTest.additionalCapabilities.*
import slick.jdbc.meta.*

class DuckDBJdbcMetaTest extends JdbcMetaTest {

  import tdb.profile.api.*

  override def testMeta = ifCap(tcap.jdbcMeta)(
    DBIO
      .seq(
        (users.schema ++ orders.schema).create
          .named("DDL used to create tables"),
        ifCap(jdbcMetaGetTypeInfo) {
          MTypeInfo.getTypeInfo
        }.named("Type info from DatabaseMetaData"),
        ifCap(tcap.jdbcMetaGetFunctions) {
          MFunction.getFunctions(MQName.local("%")).flatMap { fs =>
            DBIO
              .sequence(fs.map(_.getFunctionColumns()))
              .asInstanceOf[DBIOAction[?, NoStream, Effect.All]]
          }
        }.named("Functions from DatabaseMetaData"),
        ifCap(jdbcMetaGetUDTs) {
          MUDT.getUDTs(MQName.local("%"))
        }.named("UDTs from DatabaseMetaData"),
        MProcedure
          .getProcedures(MQName.local("%"))
          .flatMap { ps =>
            DBIO
              .sequence(ps.map(_.getProcedureColumns()))
              .asInstanceOf[DBIOAction[?, NoStream, Effect.All]]
          }
          .named("Procedures from DatabaseMetaData"),
        tdb.profile.defaultTables
          .flatMap { ts =>
            val fixtureNames = Set("users_xx", "orders_xx")
            val selectedTables =
              ts.filter(t => fixtureNames contains t.name.name)
            selectedTables.map(_.name.name).toSet shouldBe fixtureNames
            DBIO
              .sequence(
                selectedTables
                  .map { t =>
                    DBIO.seq(
                      t.getColumns.flatMap { cs =>
                        ifCap(jdbcMetaGetColumnPrivileges) {
                          val as = cs.map(_.getColumnPrivileges)
                          DBIO
                            .sequence(as)
                            .asInstanceOf[DBIOAction[?, NoStream, Effect.All]]
                        }
                      },
                      ifCap(jdbcMetaGetVersionColumns)(t.getVersionColumns),
                      t.getPrimaryKeys,
                      ifCap(jdbcMetaGetImportedKeys)(t.getImportedKeys),
                      ifCap(jdbcMetaGetExportedKeys)(t.getExportedKeys),
                      ifCap(tcap.jdbcMetaGetIndexInfo)(t.getIndexInfo()),
                      ifCap(jdbcMetaGetTablePrivileges)(t.getTablePrivileges),
                      ifCap(jdbcMetaGetBestRowIdentifier)(
                        t.getBestRowIdentifier(
                          MBestRowIdentifierColumn.Scope.Session
                        )
                      )
                    )
                  }
              )
              .asInstanceOf[DBIOAction[?, NoStream, Effect.All]]
          }
          .named("Tables from DatabaseMetaData"),
        MSchema.getSchemas.named("Schemas from DatabaseMetaData"),
        ifCap(tcap.jdbcMetaGetClientInfoProperties)(
          MClientInfoProperty.getClientInfoProperties
        ).named("Client Info Properties from DatabaseMetaData"),
        tdb.profile.defaultTables
          .map(
            _.should(ts =>
              (Set("orders_xx", "users_xx") subsetOf ts.map(_.name.name).toSet) &&
                ts.forall(_.tableType != "VIEW")
            )
          )
          .named("Tables before deleting")
      )
      .asInstanceOf[DBIOAction[Unit, NoStream, Effect.All]]
  )
}
