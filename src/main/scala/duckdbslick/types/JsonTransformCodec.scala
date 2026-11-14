package duckdbslick.types

import java.sql.{Array => SqlArray, Struct}
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import slick.SlickException

/** A fixed DuckDB JSON transformation schema and a decoder for its SQL result.
  * The callback receives JDBC nested values, with SQL-null fields left as null.
  */
final class JsonTransformCodec[A] private (
    val structure: Json,
    val sqlType: Int,
    private val decodeValue: AnyRef => A,
    val classTag: ClassTag[A]
) {
  private[duckdbslick] def decode(value: AnyRef): A =
    try decodeValue(value)
    catch {
      case NonFatal(error) =>
        throw new SlickException("Cannot decode json_transform result with the supplied codec", error)
    }
}

object JsonTransformCodec {
  def struct[A: ClassTag](structure: Json)(read: Struct => A): JsonTransformCodec[A] = {
    requireShape(structure, '{', '}', "object")
    new JsonTransformCodec[A](structure, java.sql.Types.STRUCT, {
      case value: Struct => read(value)
      case value => throw new SlickException(
        s"Expected JDBC Struct for json_transform, got ${value.getClass.getName}"
      )
    }, implicitly[ClassTag[A]])
  }

  def list[A: ClassTag](structure: Json)(read: SqlArray => A): JsonTransformCodec[A] = {
    requireShape(structure, '[', ']', "array")
    new JsonTransformCodec[A](structure, java.sql.Types.ARRAY, {
      case value: SqlArray => read(value)
      case value => throw new SlickException(
        s"Expected JDBC Array for json_transform, got ${value.getClass.getName}"
      )
    }, implicitly[ClassTag[A]])
  }

  /** Read a SQL list without losing null elements or their order. */
  def listElements[A](array: SqlArray)(read: Any => A): Vector[Option[A]] = {
    val values = array.getArray
    val size = java.lang.reflect.Array.getLength(values)
    Vector.tabulate(size) { index =>
      Option(java.lang.reflect.Array.get(values, index)).map(read)
    }
  }

  private def requireShape(structure: Json, open: Char, close: Char, name: String): Unit = {
    val text = structure.value.trim
    require(text.length >= 2 && text.head == open && text.last == close,
      s"json_transform $name structure must be a JSON $name")
    require(JsonSyntax.valid(text), s"json_transform $name structure must be valid JSON")
  }

  /** Validate configuration syntax without adding a JSON library to core. */
  private object JsonSyntax {
    def valid(text: String): Boolean = {
      val parser = new Parser(text)
      parser.parse()
    }

    private final class Parser(text: String) {
      private var at = 0

      def parse(): Boolean =
        try {
          value()
          whitespace()
          at == text.length
        } catch {
          case _: IllegalArgumentException => false
        }

      private def fail(): Nothing = throw new IllegalArgumentException("Invalid JSON")

      private def whitespace(): Unit =
        while (at < text.length && " \t\r\n".indexOf(text.charAt(at)) >= 0) at += 1

      private def take(ch: Char): Boolean = {
        whitespace()
        if (at < text.length && text.charAt(at) == ch) { at += 1; true }
        else false
      }

      private def expect(ch: Char): Unit = if (!take(ch)) fail()

      private def value(): Unit = {
        whitespace()
        if (at == text.length) fail()
        text.charAt(at) match {
          case '{' => obj()
          case '[' => array()
          case '"' => string()
          case 't' => literal("true")
          case 'f' => literal("false")
          case 'n' => literal("null")
          case '-' => number()
          case ch if ch >= '0' && ch <= '9' => number()
          case _ => fail()
        }
      }

      private def obj(): Unit = {
        expect('{')
        if (!take('}')) {
          string()
          expect(':')
          value()
          while (take(',')) {
            string()
            expect(':')
            value()
          }
          expect('}')
        }
      }

      private def array(): Unit = {
        expect('[')
        if (!take(']')) {
          value()
          while (take(',')) value()
          expect(']')
        }
      }

      private def string(): Unit = {
        expect('"')
        while (at < text.length && text.charAt(at) != '"') {
          val ch = text.charAt(at)
          if (ch < ' ') fail()
          if (ch == '\\') {
            at += 1
            if (at == text.length) fail()
            text.charAt(at) match {
              case '"' | '\\' | '/' | 'b' | 'f' | 'n' | 'r' | 't' => at += 1
              case 'u' =>
                at += 1
                for (_ <- 0 until 4) {
                  if (at == text.length || !hex(text.charAt(at))) fail()
                  at += 1
                }
              case _ => fail()
            }
          } else at += 1
        }
        if (at == text.length) fail()
        at += 1
      }

      private def number(): Unit = {
        if (text.charAt(at) == '-') at += 1
        if (at == text.length) fail()
        if (text.charAt(at) == '0') at += 1
        else {
          if (text.charAt(at) < '1' || text.charAt(at) > '9') fail()
          digits()
        }
        if (at < text.length && text.charAt(at) == '.') {
          at += 1
          val start = at
          digits()
          if (at == start) fail()
        }
        if (at < text.length && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
          at += 1
          if (at < text.length && (text.charAt(at) == '+' || text.charAt(at) == '-')) at += 1
          val start = at
          digits()
          if (at == start) fail()
        }
      }

      private def digits(): Unit =
        while (at < text.length && text.charAt(at) >= '0' && text.charAt(at) <= '9') at += 1

      private def hex(ch: Char): Boolean =
        (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') ||
          (ch >= 'A' && ch <= 'F')

      private def literal(expected: String): Unit = {
        if (!text.startsWith(expected, at)) fail()
        at += expected.length
      }
    }
  }
}
