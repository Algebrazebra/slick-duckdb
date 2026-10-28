package duckdbslick

import java.net.URLClassLoader

import org.junit.Assert.{assertNotNull, assertNull}
import org.junit.Test

class DuckDBLoggingResourcesTest {

  @Test def testProductionClasspathDoesNotConfigureLogback(): Unit = {
    val productionLocation =
      DuckDBProfile.getClass.getProtectionDomain.getCodeSource.getLocation
    val productionResources =
      new URLClassLoader(Array(productionLocation), null)

    try {
      assertNull(
        "The library must not provide an application-wide Logback configuration",
        productionResources.getResource("logback.xml")
      )
      assertNull(
        "Test logging configuration must not be packaged with the library",
        productionResources.getResource("logback-test.xml")
      )
    } finally productionResources.close()

    assertNotNull(
      "The test classpath should retain its Logback configuration",
      getClass.getResource("/logback-test.xml")
    )
  }
}
