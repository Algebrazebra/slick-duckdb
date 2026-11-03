package duckdbslick.types

/** A JSONPath or JSON Pointer that selects at most one JSON value.
  *
  * Only literal paths can be validated. Wildcards, filters, and path lists are
  * deliberately outside this type's contract.
  */
final class JsonScalarPath private (val value: String) {
  override def equals(other: Any): Boolean = other match {
    case that: JsonScalarPath => value == that.value
    case _                    => false
  }

  override def hashCode(): Int = value.hashCode
  override def toString: String = s"JsonScalarPath($value)"
}

object JsonScalarPath {
  def apply(value: String): JsonScalarPath = {
    require(value != null, "JSON scalar path cannot be null")
    require(isPointer(value) || isJsonPath(value), s"Not a single-result JSON path: $value")
    new JsonScalarPath(value)
  }

  private def isPointer(value: String): Boolean =
    value.startsWith("/") && !value.indices.exists { i =>
      value.charAt(i) == '~' &&
      (i + 1 == value.length || (value.charAt(i + 1) != '0' && value.charAt(i + 1) != '1'))
    }

  private def isJsonPath(value: String): Boolean = {
    if (!value.startsWith("$")) return false
    var i = 1
    while (i < value.length) {
      value.charAt(i) match {
        case '.' =>
          i += 1
          if (i == value.length) return false
          if (value.charAt(i) == '"') {
            i += 1
            var closed = false
            while (i < value.length && !closed) {
              if (value.charAt(i) == '\\') {
                i += 2
              } else if (value.charAt(i) == '"') {
                closed = true
                i += 1
              } else i += 1
            }
            if (!closed || i > value.length) return false
          } else {
            val start = i
            while (i < value.length && (value.charAt(i).isLetterOrDigit || value.charAt(i) == '_')) i += 1
            if (i == start) return false
          }
        case '[' =>
          i += 1
          if (i + 1 < value.length && value.charAt(i) == '#' && value.charAt(i + 1) == '-')
            i += 2
          val start = i
          while (i < value.length && value.charAt(i).isDigit) i += 1
          if (i == start || i == value.length || value.charAt(i) != ']') return false
          i += 1
        case _ => return false
      }
    }
    true
  }
}
