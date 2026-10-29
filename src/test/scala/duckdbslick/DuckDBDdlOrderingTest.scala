package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import utils.UtilityFunctions.orderDdlStatements

class DuckDBDdlOrderingTest {

  @Test def testDefaultContainingReferencesCreatesTable(): Unit = {
    class Text(tag: Tag) extends Table[String](tag, "text") {
      def value = column[String]("value", O.Default("references \"text\""))
      def *     = value
    }

    val text = TableQuery[Text]
    run(DBIO.seq(text.schema.create))
  }

  @Test def testLiteralsAndCommentsDoNotCreateDependencies(): Unit = {
    val ddl =
      """create table "text" ("value" varchar default 'references "text"', check ("value" <> 'it''s references "text"')) /* references "text" */ -- references "text""""

    assertEquals(Seq(ddl), orderDdlStatements(Vector(ddl)))
  }

  @Test def testPreservesQuotedDotAndQualifiedName(): Unit = {
    val singleName    = """create table "a.b"(id int)"""
    val qualifiedName = """create table "a"."b"(id int)"""

    val sorted = orderDdlStatements(Vector(singleName, qualifiedName))

    assertEquals(2, sorted.size)
    assertEquals(Set(singleName, qualifiedName), sorted.toSet)
  }

  @Test def testDependenciesUseDistinctQualifiedNames(): Unit = {
    val singleName = """create table "a.b"(id int)"""
    val qualifiedName = """create table "a"."b"(id int)"""
    val singleChild =
      """create table "single_child"(id int references "a.b"(id))"""
    val qualifiedChild =
      """create table "qualified_child"(id int references "a"."b"(id))"""

    val sorted = orderDdlStatements(
      Vector(qualifiedChild, singleChild, qualifiedName, singleName)
    )

    assertEquals(4, sorted.size)
    assertEquals(
      Set(singleName, qualifiedName, singleChild, qualifiedChild),
      sorted.toSet
    )
    assertTrue(sorted.indexOf(singleName) < sorted.indexOf(singleChild))
    assertTrue(sorted.indexOf(qualifiedName) < sorted.indexOf(qualifiedChild))
  }
}
