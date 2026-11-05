# JSON extraction JDBC probe

Read-only prepared statements were run against DuckDB JDBC 1.3.2.0, 1.4.1.0,
1.5.2.0, and 2.0.0.0. The JSON document was bound as `?::JSON`; each scalar
path was bound as `?`. Operators were tested with bound JSON left operands.
The [full matrix](json-extraction-jdbc-matrix.tsv) records the SQL type,
`getObject` class, `getString`, `wasNull`, and `java.sql.Array` elements for
all 264 combinations: six forms times eleven scenarios times four drivers.
The scenarios cover scalar, object, array, missing, JSON null, SQL null document,
SQL null path, wildcard, path list, JSON Pointer, and quoted JSONPath key.
The bound document and path are represented by every scalar-path row.

| Selection | SQL type | `getObject` | `getString` / `wasNull` |
| --- | --- | --- | --- |
| `json_exists`, present / missing | `BOOLEAN` | `Boolean` | `true` / `false`; false |
| `json_extract`, scalar / object / array | `JSON` | `JsonNode` | JSON text; false |
| `json_extract`, missing | `JSON` | null | null; true |
| `json_extract`, JSON null | `JSON` | `JsonNode` | `null`; false |
| `json_extract_string`, scalar | `VARCHAR` | `String` | unquoted text; false |
| `json_extract_string`, JSON null | `VARCHAR` | null | null; true |
| `json_value`, scalar | `VARCHAR` | `String` | JSON text, including quotes for a string; false |
| `json_value`, object | `VARCHAR` | null | null; true |
| `->` / `->>`, scalar | `JSON` / `VARCHAR` | `JsonNode` / `String` | JSON text / unquoted text; false |
| `json_extract`, SQL null document or path | `JSON` | null | null; true |
| JSONPath wildcard | `JSON[]` / `VARCHAR[]` | `DuckDBArray` | list text; false |
| path list | `JSON[]` / `VARCHAR[]` | `DuckDBArray` | list text; false |

JSON Pointer `/a/0` and a quoted JSONPath key `$."a.b"` returned the selected
value. A bound path worked for every canonical function and both operators.
For the wildcard `[1,null]`, `java.sql.Array.getArray` retained a null element
in both `JSON[]` and `VARCHAR[]`. The string rendering of arrays varied between
driver versions, so list decoding should use `java.sql.Array` in a later API.
The cast `json_value(...)::JSON` reported SQL `JSON` and returned a `JsonNode`
for a JSON string scalar on every tested driver.
