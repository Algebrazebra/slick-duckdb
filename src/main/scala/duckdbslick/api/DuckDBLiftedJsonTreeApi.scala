package duckdbslick.api

import duckdbslick.components.DuckDBJsonTreeCompiler.{JsonTreeIdentity, JsonTreeSource}
import duckdbslick.types.{Json, JsonPath}
import slick.ast.{AnonSymbol, Bind, ElementSymbol, Pure, Ref, Select}
import slick.jdbc.JdbcProfile
import slick.lifted.{FlatShapeLevel, Shape, ShapedValue, WrappingQuery}
import slick.util.ConstArray

private[duckdbslick] trait DuckDBLiftedJsonTreeApi extends DuckDBJsonApi {
  this: JdbcProfile#JdbcAPI =>

  /** Experimental lifted traversal with a two-column projection. */
  private[duckdbslick] def jsonTree(document: Rep[Json], path: Rep[JsonPath]): Query[
      (Rep[String], Rep[Json]), (String, Json), Seq
  ] = {
    val source = JsonTreeSource(ConstArray(document.toNode, path.toNode), new JsonTreeIdentity, jsonJdbcType)
    val gen = new AnonSymbol
    val columns = (
      Rep.forNode[String](Select(Ref(gen), ElementSymbol(1))),
      Rep.forNode[Json](Select(Ref(gen), ElementSymbol(2)))
    )
    val shape = ShapedValue(columns, implicitly[Shape[
      FlatShapeLevel, (Rep[String], Rep[Json]), (String, Json), (Rep[String], Rep[Json])
    ]])
    new WrappingQuery(Bind(gen, source, Pure(shape.toNode)), shape)
  }
}
