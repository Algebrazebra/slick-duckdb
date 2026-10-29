package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Test

class DuckDBSchemaOrderingTest {

  class Parents(tag: Tag) extends Table[Int](tag, "schema_order_parent") {
    def id = column[Int]("id", O.PrimaryKey)
    def *  = id
  }
  val parents = TableQuery[Parents]

  class Children(tag: Tag)
      extends Table[(Int, Int)](tag, "schema_order_child") {
    def id       = column[Int]("id", O.PrimaryKey)
    def parentId = column[Int]("parent_id")
    def *        = (id, parentId)
    def parent   = foreignKey("schema_order_parent_fk", parentId, parents)(_.id)
  }
  val children = TableQuery[Children]

  // A caller should be able to reuse this child-first schema for every action.
  val schema = children.schema ++ parents.schema

  @Test def testCreateThenDropChildFirstSchema(): Unit = {
    run(DBIO.seq(schema.create, schema.drop))
  }

  @Test def testConditionalCreateThenDropChildFirstSchema(): Unit = {
    run(
      DBIO.seq(
        schema.createIfNotExists,
        schema.createIfNotExists,
        schema.dropIfExists,
        schema.dropIfExists
      )
    )
  }

  @Test def testDropHonorsOverriddenStatements(): Unit = {
    class Other(tag: Tag) extends Table[Int](tag, "schema_order_other") {
      def id = column[Int]("id", O.PrimaryKey)
      def *  = id
    }
    val other = TableQuery[Other]

    run(
      DBIO.seq(
        other.schema.create,
        schema.drop.overrideStatements(Vector("drop table \"schema_order_other\"")),
        other.schema.create
      )
    )
  }
}
