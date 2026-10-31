package duckdbslick

import slick.SlickException

import java.net.JarURLConnection
import scala.util.{Try, control}

object DuckDBVersion {
  private def version(classLoader: ClassLoader): Option[(Int, Int, Int)] =
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
            bundleVersion <- Option(manifest.getMainAttributes.getValue("Bundle-Version"))
            parsed <- Try {
              val parts = bundleVersion
                .split("\\.")
                .take(3)
                .map(_.takeWhile(_.isDigit).toInt)
              (
                parts(0),
                parts.lift(1).getOrElse(0),
                parts.lift(2).getOrElse(0)
              )
            }.toOption
          } yield parsed
        case _ => None
      }
    } catch {
      case control.NonFatal(_) => None
    }

  private def supportsAtLeast(
      classLoader: ClassLoader,
      major: Int,
      minor: Int,
      patch: Int
  ): Boolean =
    version(classLoader).exists(v =>
      Ordering[(Int, Int, Int)].gteq(v, (major, minor, patch))
    )

  private def supportsVersion2(classLoader: ClassLoader): Boolean =
    supportsAtLeast(classLoader, 2, 0, 0)

  def supportsCreateModel(classLoader: ClassLoader): Boolean =
    supportsAtLeast(classLoader, 1, 5, 2)

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
