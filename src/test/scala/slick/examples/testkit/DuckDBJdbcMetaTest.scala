package slick.examples.testkit

import com.typesafe.slick.testkit.tests.JdbcMetaTest
import slick.examples.testkit.DuckDBTest.additionalCapabilities.{
  jdbcMetaGetTypeInfo,
  jdbcMetaGetUDTs
}
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
            DBIO
              .sequence(
                ts.filter(t => Set("users", "orders") contains t.name.name)
                  .map { t =>
                    DBIO.seq(
                      t.getColumns.flatMap { cs =>
                        val as = cs.map(_.getColumnPrivileges)
                        DBIO
                          .sequence(as)
                          .asInstanceOf[DBIOAction[?, NoStream, Effect.All]]
                      },
                      t.getVersionColumns,
                      t.getPrimaryKeys,
                      t.getImportedKeys,
                      t.getExportedKeys,
                      ifCap(tcap.jdbcMetaGetIndexInfo)(t.getIndexInfo()),
                      t.getTablePrivileges,
                      t.getBestRowIdentifier(
                        MBestRowIdentifierColumn.Scope.Session
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
