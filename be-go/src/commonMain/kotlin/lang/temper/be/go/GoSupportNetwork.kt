package lang.temper.be.go

import lang.temper.be.TargetLanguageTypeName
import lang.temper.be.tmpl.BubbleBranchStrategy
import lang.temper.be.tmpl.CoroutineStrategy
import lang.temper.be.tmpl.FunctionSupportCode
import lang.temper.be.tmpl.FunctionTypeStrategy
import lang.temper.be.tmpl.NamedSupportCode
import lang.temper.be.tmpl.OptionalSupportCodeKind
import lang.temper.be.tmpl.RepresentationOfVoid
import lang.temper.be.tmpl.SupportCode
import lang.temper.be.tmpl.SupportNetwork
import lang.temper.be.tmpl.TypedArg
import lang.temper.format.TokenSink
import lang.temper.lexer.Genre
import lang.temper.log.Position
import lang.temper.name.ParsedName
import lang.temper.type2.Signature2
import lang.temper.type2.Type2
import lang.temper.value.BuiltinOperatorId
import lang.temper.value.NamedBuiltinFun

object GoSupportNetwork : SupportNetwork {
    override val backendDescription = "Go backend"
    override val bubbleStrategy = BubbleBranchStrategy.IfHandlerScopeVar
    override val coroutineStrategy = CoroutineStrategy.TranslateToRegularFunction
    override val functionTypeStrategy = FunctionTypeStrategy.ToFunctionType
    override fun representationOfVoid(genre: Genre) = RepresentationOfVoid.DoNotReifyVoid

    override fun getSupportCode(
        pos: Position,
        builtin: NamedBuiltinFun,
        genre: Genre,
    ): SupportCode? = builtin.builtinOperatorId?.let { supportCodeByOperatorId(it) }

    override fun optionalSupportCode(
        optionalSupportCodeKind: OptionalSupportCodeKind,
    ): Pair<SupportCode, Signature2>? = null

    override fun translateConnectedReference(
        pos: Position,
        connectedKey: String,
        genre: Genre,
    ): SupportCode? = connectedReferences[connectedKey]

    override fun translatedConnectedType(
        pos: Position,
        connectedKey: String,
        genre: Genre,
        temperType: Type2,
    ): Pair<TargetLanguageTypeName, List<Type2>>? = null
}

private val connectedReferences: Map<String, SupportCode> = mapOf(
    "Console::log" to GoConsoleLog,
    "::getConsole" to GoGetConsole,
    "Int32::toString" to GoFmtSprint("Int32::toString"),
    "Int64::toString" to GoFmtSprint("Int64::toString"),
    "Float64::toString" to GoFmtSprint("Float64::toString"),
    "Boolean::toString" to GoFmtSprint("Boolean::toString"),
)

internal object GoConsoleLog : GoSupportCode("Console::log") {
    fun inlineToGo(
        pos: Position,
        arguments: List<TypedArg<Go.Expr>>,
        translator: GoTranslator,
    ): Go.Expr {
        // arguments[0] is `this` (the console), arguments[1] is the value to print
        val value = arguments[1].expr
        translator.needsImport("fmt")
        return Go.CallExpr(
            pos,
            fn = Go.SelectorExpr(pos, Go.Ident(pos, "fmt"), "Println"),
            args = listOf(value),
        )
    }
}

internal object GoGetConsole : GoSupportCode("::getConsole") {
    fun inlineToGo(
        pos: Position,
        @Suppress("UnusedParameter") translator: GoTranslator,
    ): Go.Expr {
        // The console object itself is not needed in Go; just return a placeholder
        return Go.Ident(pos, "_console")
    }
}

internal abstract class GoSupportCode(
    val connectedKey: String,
) : NamedSupportCode, FunctionSupportCode {
    override val baseName: ParsedName = ParsedName(connectedKey)
    override fun renderTo(tokenSink: TokenSink) = tokenSink.word(connectedKey)
}

internal class GoInfixOp(
    name: String,
    val operator: String,
) : GoSupportCode(name) {
    fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>): Go.Expr {
        return Go.BinaryExpr(pos, arguments[0].expr, operator, arguments[1].expr)
    }
}

internal class GoUnaryOp(
    name: String,
    val operator: String,
) : GoSupportCode(name) {
    fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>): Go.Expr {
        return Go.UnaryExpr(pos, operator, arguments[0].expr)
    }
}

internal class GoStrCat(name: String) : GoSupportCode(name) {
    fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>): Go.Expr {
        return Go.BinaryExpr(pos, arguments[0].expr, "+", arguments[1].expr)
    }
}

internal class GoFmtSprint(name: String) : GoSupportCode(name) {
    fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
        translator.needsImport("fmt")
        return Go.CallExpr(
            pos,
            fn = Go.SelectorExpr(pos, Go.Ident(pos, "fmt"), "Sprint"),
            args = arguments.map { it.expr },
        )
    }
}

private fun supportCodeByOperatorId(id: BuiltinOperatorId): SupportCode? = when (id) {
    // Arithmetic
    BuiltinOperatorId.PlusIntInt, BuiltinOperatorId.PlusIntInt64 -> GoInfixOp("PlusInt", "+")
    BuiltinOperatorId.PlusFltFlt -> GoInfixOp("PlusFlt", "+")
    BuiltinOperatorId.MinusIntInt, BuiltinOperatorId.MinusIntInt64 -> GoInfixOp("MinusInt", "-")
    BuiltinOperatorId.MinusFltFlt -> GoInfixOp("MinusFlt", "-")
    BuiltinOperatorId.TimesIntInt, BuiltinOperatorId.TimesIntInt64 -> GoInfixOp("TimesInt", "*")
    BuiltinOperatorId.TimesFltFlt -> GoInfixOp("TimesFlt", "*")
    BuiltinOperatorId.DivIntInt, BuiltinOperatorId.DivIntInt64,
    BuiltinOperatorId.DivIntIntSafe, BuiltinOperatorId.DivIntInt64Safe,
    -> GoInfixOp("DivInt", "/")
    BuiltinOperatorId.DivFltFlt -> GoInfixOp("DivFlt", "/")
    BuiltinOperatorId.ModIntInt, BuiltinOperatorId.ModIntInt64,
    BuiltinOperatorId.ModIntIntSafe, BuiltinOperatorId.ModIntInt64Safe,
    -> GoInfixOp("ModInt", "%")
    BuiltinOperatorId.ModFltFlt -> GoInfixOp("ModFlt", "%") // Go doesn't have float %, use math.Mod later

    // Comparison
    BuiltinOperatorId.LtIntInt, BuiltinOperatorId.LtFltFlt,
    BuiltinOperatorId.LtStrStr, BuiltinOperatorId.LtGeneric,
    -> GoInfixOp("Lt", "<")
    BuiltinOperatorId.LeIntInt, BuiltinOperatorId.LeFltFlt,
    BuiltinOperatorId.LeStrStr, BuiltinOperatorId.LeGeneric,
    -> GoInfixOp("Le", "<=")
    BuiltinOperatorId.GtIntInt, BuiltinOperatorId.GtFltFlt,
    BuiltinOperatorId.GtStrStr, BuiltinOperatorId.GtGeneric,
    -> GoInfixOp("Gt", ">")
    BuiltinOperatorId.GeIntInt, BuiltinOperatorId.GeFltFlt,
    BuiltinOperatorId.GeStrStr, BuiltinOperatorId.GeGeneric,
    -> GoInfixOp("Ge", ">=")
    BuiltinOperatorId.EqIntInt, BuiltinOperatorId.EqFltFlt,
    BuiltinOperatorId.EqStrStr, BuiltinOperatorId.EqGeneric,
    -> GoInfixOp("Eq", "==")
    BuiltinOperatorId.NeIntInt, BuiltinOperatorId.NeFltFlt,
    BuiltinOperatorId.NeStrStr, BuiltinOperatorId.NeGeneric,
    -> GoInfixOp("Ne", "!=")

    // Unary
    BuiltinOperatorId.MinusInt, BuiltinOperatorId.MinusInt64 -> GoUnaryOp("NegInt", "-")
    BuiltinOperatorId.MinusFlt -> GoUnaryOp("NegFlt", "-")
    BuiltinOperatorId.BooleanNegation -> GoUnaryOp("Not", "!")

    // Bitwise
    BuiltinOperatorId.BitwiseAnd -> GoInfixOp("BitwiseAnd", "&")
    BuiltinOperatorId.BitwiseOr -> GoInfixOp("BitwiseOr", "|")

    // String
    BuiltinOperatorId.StrCat -> GoStrCat("StrCat")

    // Not yet implemented
    BuiltinOperatorId.PowFltFlt,
    BuiltinOperatorId.CmpFltFlt, BuiltinOperatorId.CmpIntInt,
    BuiltinOperatorId.CmpStrStr, BuiltinOperatorId.CmpGeneric,
    BuiltinOperatorId.IsNull, BuiltinOperatorId.NotNull,
    BuiltinOperatorId.Bubble, BuiltinOperatorId.Panic,
    BuiltinOperatorId.Print, BuiltinOperatorId.Listify,
    BuiltinOperatorId.AdaptGeneratorFn, BuiltinOperatorId.SafeAdaptGeneratorFn,
    BuiltinOperatorId.Async,
    -> null
}
