package lang.temper.be.go

import lang.temper.log.Position

/**
 * Lightweight in-memory Go AST. Only covers constructs that Temper can express.
 * Namespace follows `Rust.*` / `Py.*` convention.
 */
object Go {

    sealed class Node(val pos: Position)

    // ── Types ─────────────────────────────────────────────────────────────────

    sealed class TypeExpr(pos: Position) : Node(pos)

    class NamedType(pos: Position, val name: String) : TypeExpr(pos)
    class PointerType(pos: Position, val base: TypeExpr) : TypeExpr(pos)
    class SliceType(pos: Position, val elem: TypeExpr) : TypeExpr(pos)
    class MapType(pos: Position, val key: TypeExpr, val value: TypeExpr) : TypeExpr(pos)
    class QualifiedType(pos: Position, val pkg: String, val name: String) : TypeExpr(pos)
    class IndexTypeExpr(pos: Position, val base: TypeExpr, val typeArgs: List<TypeExpr>) : TypeExpr(pos)
    class FuncType(pos: Position, val params: List<Field>, val results: List<TypeExpr>) : TypeExpr(pos)

    // ── Declarations ──────────────────────────────────────────────────────────

    class File(
        pos: Position,
        val packageName: String,
        val imports: List<ImportSpec>,
        val decls: List<Decl>,
    ) : Node(pos)

    class ImportSpec(pos: Position, val alias: String?, val path: String) : Node(pos)
    class Field(pos: Position, val name: String?, val type: TypeExpr) : Node(pos)

    sealed class Decl(pos: Position) : Node(pos)

    class FuncDecl(
        pos: Position,
        val name: String,
        val receiver: Field?,
        val params: List<Field>,
        val results: List<TypeExpr>,
        val body: BlockStmt,
    ) : Decl(pos)

    class TypeDecl(pos: Position, val name: String, val typeDef: TypeDef) : Decl(pos)
    class VarDecl(pos: Position, val name: String, val type: TypeExpr?, val init: Expr?) : Decl(pos)

    sealed class TypeDef(pos: Position) : Node(pos)
    class StructType(pos: Position, val fields: List<Field>) : TypeDef(pos)
    class InterfaceType(pos: Position, val methods: List<InterfaceMethod>) : TypeDef(pos)
    class InterfaceMethod(
        pos: Position,
        val name: String,
        val params: List<Field>,
        val results: List<TypeExpr>,
    ) : Node(pos)

    // ── Statements ────────────────────────────────────────────────────────────

    /** Marker for nodes valid as an else branch in Go (BlockStmt or IfStmt). */
    sealed interface ElseBranch

    sealed class Stmt(pos: Position) : Node(pos)
    class BlockStmt(pos: Position, val stmts: List<Stmt>) : Stmt(pos), ElseBranch
    class ReturnStmt(pos: Position, val results: List<Expr>) : Stmt(pos)
    class IfStmt(
        pos: Position,
        val init: Stmt?,
        val cond: Expr,
        val body: BlockStmt,
        val elseStmt: ElseBranch?,
    ) : Stmt(pos), ElseBranch
    class AssignStmt(pos: Position, val lhs: List<Expr>, val rhs: List<Expr>, val op: AssignOp) : Stmt(pos)
    enum class AssignOp { Assign, Define }
    class ExprStmt(pos: Position, val expr: Expr) : Stmt(pos)
    class DeclStmt(pos: Position, val decl: VarDecl) : Stmt(pos)

    /** Go `for` statement — covers while-loops by leaving init/post null. */
    class ForStmt(pos: Position, val init: Stmt?, val cond: Expr?, val post: Stmt?, val body: BlockStmt) : Stmt(pos)

    // ── Expressions ───────────────────────────────────────────────────────────

    sealed class Expr(pos: Position) : Node(pos)
    class Ident(pos: Position, val name: String) : Expr(pos)
    class BasicLit(pos: Position, val kind: BasicLitKind, val value: String) : Expr(pos)
    enum class BasicLitKind { Int, Float, String, Char }
    class SelectorExpr(pos: Position, val x: Expr, val sel: String) : Expr(pos)
    class CallExpr(pos: Position, val fn: Expr, val args: List<Expr>) : Expr(pos)
    class IndexExpr(pos: Position, val x: Expr, val index: Expr) : Expr(pos)
    class BinaryExpr(pos: Position, val x: Expr, val op: BinOp, val y: Expr) : Expr(pos)
    class UnaryExpr(pos: Position, val op: UnaryOp, val x: Expr) : Expr(pos)

    enum class BinOp(val symbol: String) {
        Plus("+"), Minus("-"), Times("*"), Div("/"), Mod("%"),
        Eq("=="), Ne("!="), Lt("<"), Le("<="), Gt(">"), Ge(">="),
        And("&&"), Or("||"),
        BitwiseAnd("&"), BitwiseOr("|"),
    }

    enum class UnaryOp(val symbol: String) {
        Neg("-"), Not("!"),
    }
    class CompositeLit(pos: Position, val type: TypeExpr?, val elts: List<Expr>) : Expr(pos)
    class StarExpr(pos: Position, val x: Expr) : Expr(pos)
    class AddressExpr(pos: Position, val x: Expr) : Expr(pos)
    class SliceExpr(pos: Position, val x: Expr, val low: Expr?, val high: Expr?) : Expr(pos)
    class TypeAssertExpr(pos: Position, val x: Expr, val assertType: TypeExpr) : Expr(pos)
    class FuncLit(pos: Position, val params: List<Field>, val results: List<TypeExpr>, val body: BlockStmt) : Expr(pos)
    class KeyValueExpr(pos: Position, val key: Expr, val value: Expr) : Expr(pos)
}
