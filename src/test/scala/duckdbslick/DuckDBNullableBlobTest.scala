package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import org.junit.Assert.assertEquals
import org.junit.Test

import java.sql.Blob
import javax.sql.rowset.serial.SerialBlob
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Using

class DuckDBNullableBlobTest {

  private def run[T](action: DBIO[T]): T =
    Using.resource(
      Database.forURL("jdbc:duckdb:", driver = "org.duckdb.DuckDBDriver")
    ) { db =>
      Await.result(db.run(action.withPinnedSession), 10.seconds)
    }

  private def bytes(blob: Blob): Seq[Byte] =
    blob.getBytes(1, blob.length.toInt).toSeq

  @Test def testNullableBlobsAndOuterJoin(): Unit = {
    class Blobs(tag: Tag) extends Table[(Int, Option[Blob])](tag, "blobs") {
      def id   = column[Int]("id", O.PrimaryKey)
      def data = column[Option[Blob]]("data")
      def *    = (id, data)
    }
    class Keys(tag: Tag) extends Table[Int](tag, "keys") {
      def id = column[Int]("id", O.PrimaryKey)
      def *  = id
    }
    val blobs = TableQuery[Blobs]
    val keys  = TableQuery[Keys]
    val value = Seq[Byte](1, 2, 3)

    val (stored, joined) = run(for {
      _      <- blobs.schema.create
      _      <- keys.schema.create
      _      <- blobs ++= Seq((1, Some(new SerialBlob(value.toArray))), (2, None))
      _      <- keys ++= Seq(1, 2, 3)
      stored <- blobs.sortBy(_.id).result
      joined <- keys
        .joinLeft(blobs)
        .on(_.id === _.id)
        .sortBy(_._1.id)
        .map { case (key, blob) => (key.id, blob.map(_.data)) }
        .result
    } yield (stored, joined))

    assertEquals(Seq(1, 2), stored.map(_._1))
    assertEquals(Seq(Some(value), None), stored.map(_._2.map(bytes)))
    assertEquals(Seq(1, 2, 3), joined.map(_._1))
    assertEquals(
      Seq(Some(Some(value)), Some(None), None),
      joined.map(_._2.map(_.map(bytes)))
    )
  }
}
