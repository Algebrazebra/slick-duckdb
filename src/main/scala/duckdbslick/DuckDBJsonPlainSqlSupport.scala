package duckdbslick

import slick.SlickException
import slick.jdbc.{GetResult, SetParameter}

/** Opt-in Plain SQL readers and binders for the JSON type selected by the profile. */
trait DuckDBJsonPlainSqlSupport[J] extends DuckDBProfileBase[J] {
  object plainJson extends JsonPlainImplicits

  trait JsonPlainImplicits {
    /** DuckDB JDBC binds JSON as text. Cast to JSON when SQL needs that type. */
    implicit val jsonSetParameter: SetParameter[J] = jsonOptionSetParameter.contramap(Some(_))

    implicit lazy val jsonOptionSetParameter: SetParameter[Option[J]] =
      SetParameter { (value, parameters) =>
        api.jsonJdbcType.setOption(value, parameters.ps, parameters.pos + 1)
        parameters.pos += 1
      }

    implicit val jsonGetResult: GetResult[J] = jsonOptionGetResult.andThen(_.getOrElse(
      throw new SlickException("Read SQL NULL for a required JSON result")
    ))

    implicit lazy val jsonOptionGetResult: GetResult[Option[J]] = GetResult { result =>
      val mapping = api.jsonJdbcType
      val index = result.currentPos + 1
      val value = mapping.getValue(result.rs, index)
      val isNull = mapping.wasNull(result.rs, index)
      result.skip
      if (isNull) None else Some(value)
    }
  }
}
