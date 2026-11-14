package duckdbslick.components

import duckdbslick.types.Json
import slick.ast.*
import slick.ast.TypeUtil.*
import slick.ast.Util.*
import slick.compiler.{CompilerState, MergeToComprehensions, Phase}
import slick.jdbc.JdbcType
import slick.util.ConstArray

/** Throwaway prototype for correlated JSON table functions. */
private[duckdbslick] object DuckDBJsonTreeCompiler {
  val functionName = "json_tree"

  final class JsonTreeIdentity extends AnonTableIdentitySymbol

  final case class JsonTreeSource(
      children: ConstArray[Node],
      identity: JsonTreeIdentity,
      jsonType: JdbcType[Json]
  ) extends SimplyTypedNode {
    type Self = JsonTreeSource
    override def self: Self = this
    protected def rebuild(ch: ConstArray[Node]): Self = copy(children = ch)

    val namedResultType: StructType = StructType(ConstArray(
      FieldSymbol("fullkey")(Nil, ScalaBaseType.stringType) -> ScalaBaseType.stringType,
      FieldSymbol("value")(Nil, jsonType) -> jsonType
    ))

    protected def buildType: Type =
      CollectionType(
        TypedCollectionTypeConstructor.seq,
        NominalType(identity, ProductType(ConstArray(ScalaBaseType.stringType, jsonType)))
      )
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
      if (state.tree.findNode(_.isInstanceOf[JsonTreeSource]).isEmpty)
        Phase.verifySymbols(state)
      else {
        Phase.verifySymbols(state.map(maskLateralRefs(_, Set.empty)))
        state
      }
    }
  }

  /** Carries lateral arguments through Slick's join-column remapping in the ON
    * clause. Removed before SQL generation, so it never changes the join predicate.
    */
  private final case class JoinDependencies(
      originalPaths: ConstArray[List[TermSymbol]],
      children: ConstArray[Node]
  ) extends SimplyTypedNode {
    type Self = JoinDependencies
    override def self: Self = this
    protected def rebuild(ch: ConstArray[Node]): Self = copy(children = ch)
    protected def buildType: Type = children.head.nodeType
  }

  class MergeJsonTreeSources extends MergeToComprehensions {
    override def convert(tree: Node): Node = {
      val sources = tree.collect { case source: JsonTreeSource => source.identity -> source }.toMap
      if (sources.isEmpty) super.convert(tree)
      else {
        val withDependencies = tree.replace({
          case join: Join =>
            val paths = join.right.collectAll {
              case source: JsonTreeSource =>
                source.children.flatMap(_.collect[Node] {
                  case path @ FwdPath(symbol :: _ :: _) if symbol == join.leftGen => path
                })
            }
            if (paths.isEmpty) join
            else {
              val original = paths.map { case FwdPath(path) => path }
              val on = JoinDependencies(original, ConstArray(join.on) ++ paths) :@ join.on.nodeType
              join.copy(on = on) :@ join.nodeType
            }
        }, keepType = true, bottomUp = true)

        // Slick recognizes TableNode during this phase. Keep the function arguments
        // on the source while using its column metadata for projection lowering.
        val lowered = withDependencies.replace({
          case n @ Select(base :@ NominalType(id: JsonTreeIdentity, _: ProductType), ElementSymbol(index)) =>
            val named = sources(id).namedResultType
            val field = named.elements(index - 1)._1
            Select(base.untyped :@ NominalType(id, named), field) :@ n.nodeType
          case source: JsonTreeSource =>
            TableNode(None, functionName, source.identity, source.identity)(source) :@
              CollectionType(TypedCollectionTypeConstructor.seq,
                NominalType(source.identity, source.namedResultType))
        }, keepType = true, bottomUp = true)

        val converted = super.convert(lowered)
        // Join generators are unique in the compiled tree, so each original
        // path identifies the same binding wherever it appears in an argument.
        val replacements = converted.collect { case deps: JoinDependencies => deps }
          .iterator
          .flatMap(deps => deps.originalPaths.iterator.zip(deps.children.tail.iterator))
          .toMap
        converted.replace({
          case deps: JoinDependencies => deps.children.head
          case table: TableNode if table.profileTable.isInstanceOf[JsonTreeSource] =>
            val source = table.profileTable.asInstanceOf[JsonTreeSource]
            source.mapChildren(
              _.replace({
                case path @ FwdPath(symbols) => replacements.getOrElse(symbols, path)
              }, keepType = true),
              keepType = true
            ) :@ table.nodeType
        }, keepType = true)
      }
    }
  }
}
