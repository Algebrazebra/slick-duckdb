package duckdbslick.api

import slick.ast.{Node, TypedType}
import slick.lifted.{Rep, RepShape, Shape, ShapeLevel}

/** A typed transform expression whose Slick query shape travels with it. */
final class JsonTransformRep[A](val toNode: Node, mapping: TypedType[Option[A]])
    extends Rep.TypedRep[Option[A]]()(mapping) {
  override def encodeRef(path: Node): JsonTransformRep[A] =
    new JsonTransformRep[A](path, mapping)
}

object JsonTransformRep {
  implicit def shape[A, Level <: ShapeLevel]: Shape[
    Level, JsonTransformRep[A], Option[A], JsonTransformRep[A]
  ] = RepShape[Level, JsonTransformRep[A], Option[A]]
}
