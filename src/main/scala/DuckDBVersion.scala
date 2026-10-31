package duckdbslick

import slick.SlickException

import java.net.JarURLConnection
import scala.util.{Try, control}

object DuckDBVersion {
  private def major(classLoader: ClassLoader): Option[Int] =
    try {
      val driverClass = Class.forName(
        "org.duckdb.DuckDBDriver",
        false,
        classLoader
      )
      driverClass.getResource("DuckDBDriver.class").openConnection() match {
        case jar: JarURLConnection =>
          for {
            manifest <- Option(jar.getManifest)
            version <- Option(manifest.getMainAttributes.getValue("Bundle-Version"))
            major <- Try(version.takeWhile(_ != '.').toInt).toOption
          } yield major
        case _ => None
      }
    } catch {
      case control.NonFatal(_) => None
    }

  private def supportsVersion2(classLoader: ClassLoader): Boolean =
    major(classLoader).exists(_ >= 2)

  def supportsGeneratedKeys(classLoader: ClassLoader): Boolean =
    supportsVersion2(classLoader)

  def supportsTimestampTzNs(classLoader: ClassLoader): Boolean =
    supportsVersion2(classLoader)

  def requireTimestampTzNs(classLoader: ClassLoader): Unit =
    if (!supportsTimestampTzNs(classLoader))
      throw new SlickException(
        "TIMESTAMPTZ_NS requires DuckDB JDBC 2.0 or newer"
      )
}
