package duckdbslick.api

import slick.ast.BaseTypedType
import slick.jdbc.JdbcProfile
import slick.lifted.OptionMapperDSL

/** Opt-in result mappings for JSON creation expressions. */
trait DuckDBJsonRepresentationApi extends DuckDBJsonApi {
  this: JdbcProfile#JdbcAPI =>

  /** A SQL JSON mapping for an alternate Scala representation.
    *
    * Implementations keep JSON null as a present value. `Option[J]` denotes
    * SQL NULL, so `decode` must never use a parse failure as a null value.
    */
  trait JsonRepresentation[J] {
    def jdbcType: BaseColumnType[J]
    def encode(value: J): String
    def decode(text: String): J
  }

  /** Select an alternate JSON result for a nonnullable SQL input. */
  def toJsonAs[J, T: BaseTypedType](value: Rep[T])(
      implicit representation: JsonRepresentation[J]
  ): Rep[J] = jsonResult[J](toJsonFunction, Seq(argument(value)))(representation.jdbcType)

  /** Select an alternate JSON result for a nullable SQL input. */
  def toJsonAs[J, T](value: Rep[Option[T]])(
      implicit representation: JsonRepresentation[J]
  ): Rep[Option[J]] = optionalJsonResult[J](
    toJsonFunction,
    Seq(argument(value))
  )(representation.jdbcType)

  def jsonArrayAs[J](values: Rep[?]*)(
      implicit representation: JsonRepresentation[J]
  ): Rep[J] = jsonResult[J](jsonArrayFunction, values.map(argument))(representation.jdbcType)

  def jsonObjectAs[J](fields: (Rep[String], Rep[?])*)(
      implicit representation: JsonRepresentation[J]
  ): Rep[J] = jsonResult[J](
    jsonObjectFunction,
    fields.flatMap { case (key, value) => Seq(key.toNode, argument(value)) }
  )(representation.jdbcType)

  /** Preserve the representation and the patch's nullability. */
  def jsonMergePatchAs[J, D, P](document: Rep[D], patch: Rep[P])(
      implicit representation: JsonRepresentation[J],
      documentMapping: OptionMapperDSL.arg[J, D]#to[J, D],
      patchMapping: OptionMapperDSL.arg[J, P]#to[J, P]
  ): Rep[P] = patchMapping.column(
    jsonMergePatchFunction, argument(document), argument(patch)
  )(representation.jdbcType)
}
