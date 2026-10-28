package slick.examples.testkit

import com.typesafe.slick.testkit.tests.RelationalScalarFunctionTest

class DuckDBRelationalScalarFunctionTest extends RelationalScalarFunctionTest {

  import tdb.profile.api.*

  def testIndexOfMultiplication = seq(
    Query(LiteralColumn("abc").indexOf("b") * 2).result.head shouldYield 2,
    Query(
      LiteralColumn(2) * LiteralColumn("abc").indexOf("b")
    ).result.head shouldYield 2,
    Query(LiteralColumn("abc").indexOf("x") * 2).result.head shouldYield -2,
    Query(LiteralColumn("abc").indexOf("a") * 2).result.head shouldYield 0
  )

  def testIndexOfSubtraction = seq(
    Query(
      LiteralColumn(10) - LiteralColumn("abc").indexOf("b")
    ).result.head shouldYield 9,
    Query(
      LiteralColumn(10) - LiteralColumn("abc").indexOf("x")
    ).result.head shouldYield 11,
    Query(
      LiteralColumn(10) - LiteralColumn("abc").indexOf("a")
    ).result.head shouldYield 10
  )

  def testIndexOfArithmeticInFilter = {
    class Values(tag: Tag) extends Table[String](tag, "index_of_filter") {
      def value = column[String]("value")
      def *     = value
    }
    val values = TableQuery[Values]

    seq(
      values.schema.create,
      values ++= Seq("abc", "bac", "acb", "aaa"),
      values.filter(_.value.indexOf("b") * 2 === 2).result shouldYield Seq(
        "abc"
      ),
      values
        .filter(v => LiteralColumn(10) - v.value.indexOf("b") === 9)
        .result shouldYield Seq("abc")
    )
  }
}
