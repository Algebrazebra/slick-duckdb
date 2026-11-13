package duckdbslick

import duckdbslick.DuckDBProfile.api.*
import duckdbslick.types.{Json, JsonPath}
import slick.ast.*
import slick.ast.TypeUtil.*
import slick.ast.Util.*
import slick.compiler.{CompilerState, MergeToComprehensions, Phase}
import slick.lifted.{FlatShapeLevel, Shape, ShapedValue, WrappingQuery}
import slick.util.ConstArray

/** Throwaway prototype for correlated JSON table functions. */
private[duckdbslick] object JsonTableFunctionPrototype {
  private val fullKey = FieldSymbol("fullkey")(Nil, ScalaBaseType.stringType)
  private val value = FieldSymbol("value")(Nil, jsonJdbcType)

  private val resultType = ProductType(ConstArray(fullKey.tpe, value.tpe))
  private val namedResultType = StructType(ConstArray(fullKey -> fullKey.tpe, value -> value.tpe))

  final class JsonTreeIdentity extends AnonTableIdentitySymbol

  final case class JsonTreeSource(children: ConstArray[Node], identity: JsonTreeIdentity)
      extends SimplyTypedNode {
    type Self = JsonTreeSource
    override def self: Self = this
    protected def rebuild(ch: ConstArray[Node]): Self = copy(children = ch)
    protected def buildType: Type =
      CollectionType(
        TypedCollectionTypeConstructor.seq,
        NominalType(identity, resultType)
      )
  }

  def jsonTree(document: Rep[Json], path: Rep[JsonPath]): Query[
      (Rep[String], Rep[Json]), (String, Json), Seq
  ] = {
    val source = JsonTreeSource(ConstArray(document.toNode, path.toNode), new JsonTreeIdentity)
    val gen = new AnonSymbol
    val columns = (
      Rep.forNode[String](Select(Ref(gen), ElementSymbol(1))),
      Rep.forNode[Json](Select(Ref(gen), ElementSymbol(2)))
    )
    val shape = ShapedValue(columns, implicitly[Shape[
      FlatShapeLevel, (Rep[String], Rep[Json]), (String, Json), (Rep[String], Rep[Json])
    ]])
    new WrappingQuery(Bind(gen, source, Pure(shape.toNode)), shape)
  }

  object VerifyLateralSymbols extends Phase {
    val name = Phase.verifySymbols.name

    def apply(state: CompilerState): CompilerState = {
      def maskLateralRefs(node: Node, leftGenerators: Set[TermSymbol]): Node = node match {
        case join: Join =>
          join.copy(
            left = maskLateralRefs(join.left, leftGenerators),
            right = maskLateralRefs(join.right, leftGenerators + join.leftGen),
            on = maskLateralRefs(join.on, leftGenerators)
          )
        case source: JsonTreeSource =>
          source.mapChildren(_.replace {
            case ref: Ref if leftGenerators(ref.sym) =>
              // The stock verifier only inspects symbols. These replacements are
              // scoped to validation and are never passed to later compiler phases.
              LiteralNode(0)
          })
        case other => other.mapChildren(maskLateralRefs(_, leftGenerators))
      }
      Phase.verifySymbols(state.map(maskLateralRefs(_, Set.empty)))
      state
    }
  }

  class MergeJsonTreeSources extends MergeToComprehensions {
    override def convert(tree: Node): Node = {
      // Adapt only during Slick's source lowering, where the built-in relation
      // matcher recognizes TableNode but offers no extension hook for sources.
      val lowered = tree.replace({
        case n @ Select(base :@ NominalType(id: JsonTreeIdentity, _: ProductType), ElementSymbol(index)) =>
          val field = namedResultType.elements(index - 1)._1
          Select(base.untyped :@ NominalType(id, namedResultType), field) :@ n.nodeType
        case source: JsonTreeSource =>
          TableNode(None, "json_tree", source.identity, source.identity)(source) :@
            CollectionType(TypedCollectionTypeConstructor.seq,
              NominalType(source.identity, namedResultType))
      }, keepType = true)
      super.convert(lowered).replace({
        case table: TableNode if table.profileTable.isInstanceOf[JsonTreeSource] =>
          table.profileTable.asInstanceOf[JsonTreeSource] :@ table.nodeType
      }, keepType = true)
    }
  }
}
