package duckdbslick.types

import scala.reflect.ClassTag

/** Encodes and decodes SQL JSON text. Decoding the text "null" must return
  * a present J. Throw if the text cannot be decoded.
  */
final case class JsonCodec[J: ClassTag](encode: J => String, decode: String => J) {
  private[duckdbslick] val classTag: ClassTag[J] = implicitly[ClassTag[J]]
}
