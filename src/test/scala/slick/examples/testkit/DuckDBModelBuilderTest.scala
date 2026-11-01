package slick.examples.testkit

import com.typesafe.slick.testkit.tests.ModelBuilderTest
import org.junit.Assert.{assertEquals, assertTrue}
import slick.ast.ColumnOption
import slick.relational.RelationalProfile

class DuckDBModelBuilderTest extends ModelBuilderTest {

  import tdb.profile.api.*

  // DuckDB does not report VARCHAR lengths through JDBC metadata, and Slick's
  // strict parser cannot read the nextval(...) default backing AutoInc columns.
  // Check the metadata DuckDB does provide, including Boolean defaults.
  override def test = ifCap(jcap.createModel) {
    for {
      _ <- (posts.schema ++ categories.schema ++ defaultTest.schema ++ noDefaultTest.schema ++ typeTest.schema).create
      model <- tdb.profile.createModel()
    } yield {
      model.assertConsistency()
      assertEquals(5, model.tables.size)

      val categoriesModel = model.tables.find(_.name.table.toUpperCase == "CATEGORIES").get
      assertTrue(
        categoriesModel.columns.find(_.name == "id").get.options.contains(ColumnOption.PrimaryKey)
      )

      val postsModel = model.tables.find(_.name.table.toUpperCase == "POSTS").get
      assertEquals(2, postsModel.primaryKey.get.columns.size)
      assertEquals(1, postsModel.foreignKeys.size)
      assertEquals("CATEGORIES", postsModel.foreignKeys.head.referencedTable.table.toUpperCase)

      val defaultsModel = model.tables.find(_.name.table.toUpperCase == "DEFAULT_TEST").get
      def default(name: String): Option[Any] =
        defaultsModel.columns.find(_.name == name).get.options.collectFirst {
          case RelationalProfile.ColumnOption.Default(value) => value
        }
      assertEquals(Some(true), default("some_bool_default_true"))
      assertEquals(Some(false), default("some_bool_default_false"))
      assertEquals(Some(Some(true)), default("some_bool_option_default_some"))

      val typesModel = model.tables.find(_.name.table.toUpperCase == "TYPE_TEST").get
      val types = typesModel.columns.map(c => c.name.toUpperCase -> c.tpe).toMap
      assertEquals("Int", types("INT"))
      assertEquals("Boolean", types("BOOLEAN"))
      assertEquals("java.sql.Date", types("JAVA_SQL_DATE"))
      assertEquals("java.sql.Blob", types("JAVA_SQL_BLOB"))
    }
  }
}
