package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import utils.UtilityFunctions.{TableDefinition, TableId, orderCreateDdlStatements, orderDropDdlStatements}

import scala.concurrent.ExecutionContext.Implicits.global

class DuckDBDdlOrderingTest {

  private def definition(id: TableId, dependencies: Set[TableId] = Set.empty): TableDefinition = {
    val name = (id.schema.toSeq :+ id.name)
      .map(part => "\"" + part.replace("\"", "\"\"") + "\"")
      .mkString(".")
    TableDefinition(
      id,
      dependencies,
      s"create table $name(id int)",
      s"create table if not exists $name(id int)",
      s"drop table $name",
      s"drop table if exists $name"
    )
  }

  @Test def testDefaultTextCannotInventForeignKeyCycle(): Unit = {
    class Parent(tag: Tag) extends Table[(Int, String)](tag, "parent") {
      def id   = column[Int]("id", O.PrimaryKey)
      def note = column[String](
        "note",
        O.Default("references \"child\""),
        O.Check[String]("note_check")(_ =!= "mentions references \"child\"")
      )
      def *    = (id, note)
    }
    val parents = TableQuery[Parent]

    class Child(tag: Tag) extends Table[(Int, Int)](tag, "child") {
      def id       = column[Int]("id", O.PrimaryKey)
      def parentId = column[Int]("parent_id")
      def *        = (id, parentId)
      def parentFK = foreignKey("child_parent_fk", parentId, parents)(_.id)
    }
    val children = TableQuery[Child]

    val schema = children.schema ++ parents.schema
    run(DBIO.seq(schema.create, schema.drop, schema.createIfNotExists, schema.dropIfExists))
  }

  @Test def testCheckTextCannotInventForeignKeyCycle(): Unit = {
    val parent = definition(TableId(None, "parent")).copy(
      create = """create table "parent"(id int, note varchar check (note <> 'references "child"'))"""
    )
    val child = definition(TableId(None, "child"), Set(parent.id)).copy(
      create = """create table "child"(id int references "parent"(id))"""
    )

    val sorted = orderCreateDdlStatements(
      Vector(child.create, parent.create),
      Vector(child, parent),
      false
    )
    assertEquals(Seq(parent.create, child.create), sorted)
  }

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
    val aId = TableId(None, "a")
    val bId = TableId(None, "b")
    val a   = definition(aId, Set(bId))
    val b   = definition(bId, Set(aId))

    try {
      orderCreateDdlStatements(Vector(a.create, b.create), Vector(a, b), false)
      org.junit.Assert.fail("Expected a dependency cycle")
    } catch {
      case e: IllegalArgumentException =>
        assertEquals("Cyclic dependency detected.", e.getMessage)
    }
  }

  @Test def testPreservesQuotedDotAndQualifiedName(): Unit = {
    val singleName    = definition(TableId(None, "a.b"))
    val qualifiedName = definition(TableId(Some("a"), "b"))

    val sorted = orderCreateDdlStatements(
      Vector(singleName.create, qualifiedName.create),
      Vector(singleName, qualifiedName),
      false
    )

    assertEquals(2, sorted.size)
    assertEquals(Set(singleName.create, qualifiedName.create), sorted.toSet)
  }

  @Test def testDependenciesUseDistinctQualifiedNames(): Unit = {
    val singleName     = definition(TableId(None, "a.b"))
    val qualifiedName  = definition(TableId(Some("a"), "b"))
    val singleChild    = definition(TableId(None, "single_child"), Set(singleName.id))
    val qualifiedChild = definition(TableId(None, "qualified_child"), Set(qualifiedName.id))

    val sorted = orderCreateDdlStatements(
      Vector(qualifiedChild.create, singleChild.create, qualifiedName.create, singleName.create),
      Vector(qualifiedChild, singleChild, qualifiedName, singleName),
      false
    )

    assertEquals(4, sorted.size)
    assertEquals(
      Set(singleName.create, qualifiedName.create, singleChild.create, qualifiedChild.create),
      sorted.toSet
    )
    assertTrue(sorted.indexOf(singleName.create) < sorted.indexOf(singleChild.create))
    assertTrue(sorted.indexOf(qualifiedName.create) < sorted.indexOf(qualifiedChild.create))
  }

  @Test def testDropOrderUsesDistinctQualifiedNames(): Unit = {
    val singleName    = definition(TableId(None, "a.b"))
    val qualifiedName = definition(TableId(Some("a"), "b"))
    val child         = definition(TableId(None, "child"), Set(qualifiedName.id))
    val dropSequence = "drop sequence child_id_seq"

    val sorted = orderDropDdlStatements(
      Vector(singleName.dropIfExists, qualifiedName.dropIfExists, child.dropIfExists, dropSequence),
      Vector(child, singleName, qualifiedName),
      true
    )

    assertEquals(
      Set(singleName.dropIfExists, qualifiedName.dropIfExists, child.dropIfExists, dropSequence),
      sorted.toSet
    )
    assertTrue(sorted.indexOf(child.dropIfExists) < sorted.indexOf(qualifiedName.dropIfExists))
    assertEquals(dropSequence, sorted.last)
  }
}
