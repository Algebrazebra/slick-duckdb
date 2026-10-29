package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import utils.UtilityFunctions.{orderDdlStatements, orderDropDdlStatements}

import scala.concurrent.ExecutionContext.Implicits.global

class DuckDBDdlOrderingTest {

  @Test def testSelfReferencingForeignKeyCanCreateAndInsertRows(): Unit = {
    class Nodes(tag: Tag)
        extends Table[(Int, Option[Int])](tag, "self_referencing_nodes") {
      def id       = column[Int]("id", O.PrimaryKey)
      def parentId = column[Option[Int]]("parent_id")
      def *        = (id, parentId)
      def parentFK =
        foreignKey("nodes_parent_fk", parentId, nodes)(_.id.?)
    }
    lazy val nodes = TableQuery[Nodes]

    val rows = run(for {
      _    <- nodes.schema.create
      _    <- nodes ++= Seq((1, None), (2, Some(1)))
      rows <- nodes.sortBy(_.id).result
    } yield rows)

    assertEquals(Seq((1, None), (2, Some(1))), rows)
  }

  @Test def testDistinctTableCycleIsRejected(): Unit = {
    val a = """create table "a"(id int references "b"(id))"""
    val b = """create table "b"(id int references "a"(id))"""

    try {
      orderDdlStatements(Vector(a, b))
      org.junit.Assert.fail("Expected a dependency cycle")
    } catch {
      case e: IllegalArgumentException =>
        assertEquals("Cyclic dependency detected.", e.getMessage)
    }
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

  @Test def testDropOrderUsesDistinctQualifiedNames(): Unit = {
    val singleName = """create table "a.b"(id int)"""
    val qualifiedName = """create table "a"."b"(id int)"""
    val child =
      """create table "child"(id int references "a"."b"(id))"""
    val dropSingle = """drop table if exists "a.b""""
    val dropQualified = """drop table if exists "a"."b""""
    val dropChild = """drop table if exists "child""""
    val dropSequence = "drop sequence child_id_seq"

    val sorted = orderDropDdlStatements(
      Vector(child, singleName, qualifiedName),
      Vector(dropSingle, dropQualified, dropChild, dropSequence)
    )

    assertEquals(
      Set(dropSingle, dropQualified, dropChild, dropSequence),
      sorted.toSet
    )
    assertTrue(sorted.indexOf(dropChild) < sorted.indexOf(dropQualified))
    assertEquals(dropSequence, sorted.last)
  }
}
