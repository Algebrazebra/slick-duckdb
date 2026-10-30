package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.assertEquals
import org.junit.Test

import scala.concurrent.ExecutionContext.Implicits.global

class DuckDBAutoIncSchemaLifecycleTest {

  class ConditionalCreate(tag: Tag)
      extends Table[(Int, String)](tag, "autoinc_conditional_create") {
    def id   = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def *    = (id, name)
  }
  val conditionalCreate = TableQuery[ConditionalCreate]

  @Test def testRepeatedCreateIfNotExistsPreservesSequenceCounter(): Unit = {
    val rows = run(for {
      _    <- conditionalCreate.schema.createIfNotExists
      _    <- conditionalCreate.map(_.name) += "first"
      _    <- conditionalCreate.schema.createIfNotExists
      _    <- conditionalCreate.map(_.name) += "second"
      rows <- conditionalCreate.sortBy(_.id).result
    } yield rows)

    assertEquals(Seq((1, "first"), (2, "second")), rows)
  }

  class ConditionalDrop(tag: Tag)
      extends Table[Int](tag, "autoinc_conditional_drop") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def *  = id
  }
  val conditionalDrop = TableQuery[ConditionalDrop]

  private val backingSequenceNames = sql"""
    select sequence_name
    from duckdb_sequences()
    where sequence_name = 'autoinc_conditional_drop_id_seq'
  """.as[String]

  @Test def testDropIfExistsRemovesSequenceBeforeRecreate(): Unit = {
    val (afterFirstDrop, afterSecondDrop, afterRecreate) = run(for {
      _               <- conditionalDrop.schema.create
      _               <- conditionalDrop.schema.dropIfExists
      afterFirstDrop  <- backingSequenceNames
      _               <- conditionalDrop.schema.dropIfExists
      afterSecondDrop <- backingSequenceNames
      _               <- conditionalDrop.schema.create
      afterRecreate   <- backingSequenceNames
    } yield (afterFirstDrop, afterSecondDrop, afterRecreate))

    assertEquals(Seq.empty[String], afterFirstDrop)
    assertEquals(Seq.empty[String], afterSecondDrop)
    assertEquals(Seq("autoinc_conditional_drop_id_seq"), afterRecreate)
  }
}
