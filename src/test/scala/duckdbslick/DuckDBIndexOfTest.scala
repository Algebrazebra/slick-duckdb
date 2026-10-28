package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.DuckDBTestDatabase.run
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import scala.concurrent.ExecutionContext.Implicits.global

class DuckDBIndexOfTest {

  @Test def testIndexOfMultiplication(): Unit = {
    val actual = run(
      DBIO.sequence(
        Seq(
          Query(LiteralColumn("abc").indexOf("b") * 2).result.head,
          Query(
            LiteralColumn(2) * LiteralColumn("abc").indexOf("b")
          ).result.head,
          Query(LiteralColumn("abc").indexOf("x") * 2).result.head,
          Query(LiteralColumn("abc").indexOf("a") * 2).result.head
        )
      )
    )

    assertEquals(Seq(2, 2, -2, 0), actual)
  }

  @Test def testIndexOfSubtraction(): Unit = {
    val actual = run(
      DBIO.sequence(
        Seq(
          Query(
            LiteralColumn(10) - LiteralColumn("abc").indexOf("b")
          ).result.head,
          Query(
            LiteralColumn(10) - LiteralColumn("abc").indexOf("x")
          ).result.head,
          Query(
            LiteralColumn(10) - LiteralColumn("abc").indexOf("a")
          ).result.head
        )
      )
    )

    assertEquals(Seq(9, 11, 10), actual)
  }

  @Test def testIndexOfArithmeticInFilter(): Unit = {
    class Values(tag: Tag) extends Table[String](tag, "index_of_filter") {
      def value = column[String]("value")
      def *     = value
    }
    val values = TableQuery[Values]

    val (multiplication, subtraction) = run(for {
      _              <- values.schema.create
      _              <- values ++= Seq("abc", "bac", "acb", "aaa")
      multiplication <- values.filter(_.value.indexOf("b") * 2 === 2).result
      subtraction    <-
        values
          .filter(v => LiteralColumn(10) - v.value.indexOf("b") === 9)
          .result
    } yield (multiplication, subtraction))

    assertEquals(Seq("abc"), multiplication)
    assertEquals(Seq("abc"), subtraction)
  }

  @Test def testIndexOfArithmeticInCheckConstraint(): Unit = {
    class CheckedIndexOf(tag: Tag)
        extends Table[String](tag, "checked_index_of") {
      def value = column[String](
        "value",
        O.Check[String]("index_of_multiplication")(_.indexOf("b") * 2 === 2),
        O.Check[String]("index_of_subtraction")(value =>
          LiteralColumn(10) - value.indexOf("b") === 9
        )
      )
      def *     = value
    }
    val values = TableQuery[CheckedIndexOf]

    val (rejected, stored) = run(for {
      _        <- values.schema.create
      _        <- values += "abc"
      rejected <-
        DBIO.sequence(
          Seq("bac", "acb", "aaa").map(value => (values += value).asTry)
        )
      stored   <- values.result
    } yield (rejected, stored))

    assertTrue(rejected.forall(_.isFailure))
    assertEquals(Seq("abc"), stored)
  }
}
