package duckdbslick

import duckdbslick.types.JsonPath
import slick.SlickException
import slick.jdbc.{GetResult, SetParameter}

/** Opt-in Plain SQL readers and binders for the JSON type selected by the profile. */
trait DuckDBJsonPlainSqlSupport[J] extends DuckDBProfileBase[J] {
  object plainJson extends JsonPlainImplicits

  trait JsonPlainImplicits {
    /** Plain SQL parameter binding preserves the supplied path text. */
    implicit val jsonPathSetParameter: SetParameter[JsonPath] =
      SetParameter[JsonPath]((value, parameters) =>
        parameters.setString(value.value))

    /** Convert a JSON Pointer for json_each/json_tree, which require JSONPath.
      * Use explicit JSONPath for numeric tokens to distinguish indexes from keys.
      * For an empty key or a key named "*", use json_each on the parent object
      * and filter by key.
      */
    def jsonTablePath(path: JsonPath): JsonPath = {
      val text = path.value
      if (text.isEmpty) JsonPath("$")
      else if (!text.startsWith("/")) path
      else JsonPath(text.split("/", -1).drop(1).foldLeft("$") { (result, token) =>
        require(!token.matches("(?s).*~(?![01]).*"),
          "Invalid JSON Pointer escape in table-function path")
        val decoded = token.replace("~1", "/").replace("~0", "~")
        require(decoded.nonEmpty && decoded != "*",
          "Empty and '*' JSON Pointer tokens are unsupported for table functions. " +
            "Use json_each on the parent object and filter by key.")
        require(!decoded.matches("[0-9]+"),
          "Numeric JSON Pointer tokens need an explicit JSONPath for table functions")
        result + ".\"" + decoded.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      })
    }

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
