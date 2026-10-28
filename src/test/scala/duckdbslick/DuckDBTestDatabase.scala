package duckdbslick

import duckdbslick.DuckDBProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Using

private[duckdbslick] object DuckDBTestDatabase {
  def run[T](action: DBIO[T]): T =
    Using.resource(
      Database.forURL("jdbc:duckdb:", driver = "org.duckdb.DuckDBDriver")
    ) { db =>
      // One connection keeps the in-memory database shared by all statements.
      Await.result(db.run(action.withPinnedSession), 10.seconds)
    }
}
