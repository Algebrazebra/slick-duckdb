package duckdbslick

import java.net.JarURLConnection
import scala.util.{Try, control}

object DuckDBDriverVersion {
  def major(classLoader: ClassLoader): Option[Int] =
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
}
