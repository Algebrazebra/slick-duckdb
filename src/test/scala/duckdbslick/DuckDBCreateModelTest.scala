package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import slick.ast.ColumnOption
import slick.jdbc.JdbcCapabilities
import slick.model.Model

import java.sql.SQLFeatureNotSupportedException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class DuckDBCreateModelTest {

  private def driverSupportsCreateModel: Boolean = {
    val version = run(
      SimpleDBIO(_.connection.getMetaData.getDatabaseProductVersion)
    ).stripPrefix("v").split("\\.").take(3).map(_.toInt)
    Ordering[(Int, Int, Int)].gteq(
      (version(0), version(1), version(2)),
      (1, 5, 2)
    )
  }

  @Test def testCapabilityFollowsDriverVersion(): Unit =
    assertEquals(
      driverSupportsCreateModel,
      DuckDBProfile.capabilities.contains(JdbcCapabilities.createModel)
    )

  private def checkModel(result: Try[Model])(check: Model => Unit): Unit =
    if (driverSupportsCreateModel) check(result.get)
    else {
      assertTrue(
        result.failed.toOption.exists {
          case e: SQLFeatureNotSupportedException => e.getMessage == "getImportedKeys"
          case _                                  => false
        }
      )
    }

  @Test def testPrimaryKeyAndColumnTypes(): Unit = {
    val result = Try(run(for {
      _ <- sqlu"""create table model_parents (
        id integer primary key,
        name varchar not null,
        active boolean not null,
        amount decimal(12, 2),
        created_on date,
        recorded_at timestamp
      )"""
      model <- DuckDBProfile.createModel()
    } yield model))

    checkModel(result) { model =>
      model.assertConsistency()
      val table = model.tables.find(_.name.table == "model_parents").get
      assertEquals(1, model.tables.size)
      assertEquals(
        Seq("id", "name", "active", "amount", "created_on", "recorded_at"),
        table.columns.map(_.name)
      )
      assertTrue(
        table.columns
          .find(_.name == "id")
          .get
          .options
          .contains(ColumnOption.PrimaryKey)
      )
      assertEquals(
        Seq(
          "Int",
          "String",
          "Boolean",
          "scala.math.BigDecimal",
          "java.sql.Date",
          "java.sql.Timestamp"
        ),
        table.columns.map(_.tpe)
      )
      assertEquals(
        Seq(false, false, false, true, true, true),
        table.columns.map(_.nullable)
      )
    }
  }

  @Test def testForeignKey(): Unit = {
    val result = Try(run(for {
      _ <- sqlu"""create table model_parents (id integer primary key)"""
      _ <- sqlu"""create table model_children (
        id bigint primary key,
        parent_id integer not null,
        score double,
        foreign key (parent_id) references model_parents(id)
      )"""
      model <- DuckDBProfile.createModel()
    } yield model))

    checkModel(result) { model =>
      model.assertConsistency()
      val child = model.tables.find(_.name.table == "model_children").get
      val fk = child.foreignKeys.head
      assertEquals(2, model.tables.size)
      assertEquals(1, child.foreignKeys.size)
      assertEquals(Seq("parent_id"), fk.referencingColumns.map(_.name))
      assertEquals("model_parents", fk.referencedTable.table)
      assertEquals(Seq("id"), fk.referencedColumns.map(_.name))
      assertEquals(Seq("Long", "Int", "Double"), child.columns.map(_.tpe))
    }
  }
}
