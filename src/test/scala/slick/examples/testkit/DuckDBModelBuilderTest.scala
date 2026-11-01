package slick.examples.testkit

import com.typesafe.slick.testkit.tests.ModelBuilderTest
import org.junit.Assert.{assertEquals, assertTrue}
import slick.ast.ColumnOption
import slick.model.Model
import slick.relational.RelationalProfile
import slick.sql.SqlProfile

class DuckDBModelBuilderTest extends ModelBuilderTest {

  import tdb.profile.api.*

  private def columnDefault(table: slick.model.Table, name: String): Option[Any] =
    table.columns.find(_.name.equalsIgnoreCase(name)).get.options.collectFirst {
      case RelationalProfile.ColumnOption.Default(value) => value
    }

  // DuckDB does not report VARCHAR lengths through JDBC metadata, and Slick's
  // strict parser cannot read the nextval(...) default backing AutoInc columns.
  // Check the metadata DuckDB does provide, including Boolean defaults.
  override def test = ifCap(jcap.createModel) {
    for {
      _ <- (posts.schema ++ categories.schema ++ defaultTest.schema ++ noDefaultTest.schema ++ typeTest.schema).create
      model <- tdb.profile.createModel()
      tables <- tdb.profile.defaultTables
      postTables = tables.filter(_.name.name.equalsIgnoreCase("posts"))
      postsOnly <- tdb.profile.createModel(Some(DBIO.successful(postTables)))
      categoryTables = tables.filter(_.name.name.equalsIgnoreCase("categories"))
      categoriesOnly <- tdb.profile.createModel(Some(DBIO.successful(categoryTables)))
      _ <- (noDefaultTest.map(_.int) += 1)
      stringOption <- noDefaultTest.map(_.stringOption).result.head
    } yield {
      model.assertConsistency()
      assertEquals(5, model.tables.size)
      assertEquals(None, stringOption)

      postsOnly.assertConsistency()
      assertEquals(1, postsOnly.tables.size)
      assertTrue(postsOnly.tables.head.foreignKeys.isEmpty)
      categoriesOnly.assertConsistency()
      assertEquals(1, categoriesOnly.tables.size)
      Model(model.tables.filter(_.name.table.equalsIgnoreCase("posts")))
        .shouldFail(_.assertConsistency())

      val categoriesModel = model.tables.find(_.name.table.toUpperCase == "CATEGORIES").get
      assertEquals(2, categoriesModel.columns.size)
      assertEquals(None, categoriesModel.primaryKey)
      assertTrue(categoriesModel.foreignKeys.isEmpty)
      assertEquals(
        Seq("id"),
        categoriesModel.columns.filter(_.options.contains(ColumnOption.PrimaryKey)).map(_.name)
      )

      val postsModel = model.tables.find(_.name.table.toUpperCase == "POSTS").get
      assertEquals(5, postsModel.columns.size)
      assertTrue(postsModel.indices.isEmpty)
      assertEquals(Seq("id", "title"), postsModel.primaryKey.get.columns.map(_.name))
      assertTrue(postsModel.columns.forall(!_.options.contains(ColumnOption.PrimaryKey)))
      assertEquals(1, postsModel.foreignKeys.size)
      val categoryFK = postsModel.foreignKeys.head
      // DuckDB assigns its own foreign-key name instead of Slick's category_fk.
      assertTrue(categoryFK.name.exists(_.nonEmpty))
      assertEquals(Seq("category"), categoryFK.referencingColumns.map(_.name))
      assertEquals("CATEGORIES", categoryFK.referencedTable.table.toUpperCase)
      assertEquals(Seq("id"), categoryFK.referencedColumns.map(_.name))
      assertEquals(
        Some("VARCHAR"),
        postsModel.columns.find(_.name == "some_string").get.options.collectFirst {
          case SqlProfile.ColumnOption.SqlType(name) => name.toUpperCase
        }
      )

      val defaultsModel = model.tables.find(_.name.table.toUpperCase == "DEFAULT_TEST").get
      assertTrue(tdb.profile.capabilities.contains(jcap.nullableNoDefault))
      assertTrue(defaultsModel.name.schema.forall(!_.equalsIgnoreCase("PUBLIC")))
      assertTrue(defaultsModel.name.catalog.forall(!_.equalsIgnoreCase("PUBLIC")))
      def default(name: String): Option[Any] = columnDefault(defaultsModel, name)
      assertEquals(None, default("some_bool"))
      assertEquals(Some(true), default("some_bool_default_true"))
      assertEquals(Some(false), default("some_bool_default_false"))
      assertEquals(None, default("some_bool_option"))
      assertEquals(Some(Some(true)), default("some_bool_option_default_some"))
      assertEquals(Some(None), default("some_bool_option_default_none"))
      assertEquals(None, default("some_string"))
      assertEquals(Some("bar"), default("some_string_default_non_empty"))
      assertEquals(Some(""), default("some_string_default_empty"))
      assertEquals(None, default("some_string_option"))
      assertEquals(Some(Some("")), default("str_option_default_empty"))
      assertEquals(Some(None), default("str_option_default_none"))
      assertEquals(Some(Some("foo")), default("str_option_default_non_empty"))

      val typesModel = model.tables.find(_.name.table.toUpperCase == "TYPE_TEST").get
      val types = typesModel.columns.map(c => c.name.toUpperCase -> c).toMap
      def checkTypePair(name: String, expectedType: String): Unit = {
        val required = types(name.toUpperCase)
        val optional = types(s"Option_$name".toUpperCase)
        assertEquals(s"$name type", expectedType, required.tpe)
        assertEquals(s"Option_$name type", expectedType, optional.tpe)
        assertEquals(s"$name nullability", false, required.nullable)
        assertEquals(s"Option_$name nullability", true, optional.nullable)
      }

      checkTypePair("Boolean", "Boolean")
      ifCapU(jcap.supportsByte) { checkTypePair("Byte", "Byte") }
      ifCapU(jcap.distinguishesIntTypes) { checkTypePair("Short", "Short") }
      checkTypePair("Int", "Int")
      ifCapU(jcap.distinguishesIntTypes) { checkTypePair("Long", "Long") }
      checkTypePair("Double", "Double")
      checkTypePair("String", "String")
      checkTypePair("java_sql_Date", "java.sql.Date")
      checkTypePair("java_sql_Time", "java.sql.Time")
      checkTypePair("java_sql_Timestamp", "java.sql.Timestamp")
      checkTypePair("java_sql_Blob", "java.sql.Blob")

      assertEquals(Some(-5), columnDefault(typesModel, "Int"))
      assertEquals(Some(Some(5)), columnDefault(typesModel, "Option_Int"))
      assertEquals(Some(5L), columnDefault(typesModel, "Long"))
      assertEquals(Some(Some(-5L)), columnDefault(typesModel, "Option_Long"))

      val noDefaultsModel = model.tables.find(_.name.table.toUpperCase == "NO_DEFAULT_TEST").get
      assertEquals(None, columnDefault(noDefaultsModel, "stringOption"))
      assertEquals(Some(None), columnDefault(noDefaultsModel, "stringOptionDefaultNone"))
    }
  }
}
