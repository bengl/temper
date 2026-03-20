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
    ): SupportCode? = builtin.builtinOperatorId?.let { supportCodeByOperatorId[it] }

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
    "String::isEmpty" to GoTemperCoreFunc("String::isEmpty", "StringIsEmpty"),
    "Int32::min" to GoTemperCoreFunc("Int32::min", "IntMin"),
    "Int32::max" to GoTemperCoreFunc("Int32::max", "IntMax"),
)

internal sealed class GoSupportCode(
    val connectedKey: String,
) : NamedSupportCode, FunctionSupportCode {
    override val baseName: ParsedName = ParsedName(connectedKey)
    override fun renderTo(tokenSink: TokenSink) = tokenSink.word(connectedKey)

    abstract fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr?
}

internal object GoConsoleLog : GoSupportCode("Console::log") {
    override fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
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
    override fun inlineToGo(
        pos: Position,
        @Suppress("UnusedParameter") arguments: List<TypedArg<Go.Expr>>,
        @Suppress("UnusedParameter") translator: GoTranslator,
    ): Go.Expr {
        return Go.Ident(pos, "_console")
    }
}

internal class GoInfixOp(
    name: String,
    val operator: Go.BinOp,
) : GoSupportCode(name) {
    override fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
        return Go.BinaryExpr(pos, arguments[0].expr, operator, arguments[1].expr)
    }
}

internal class GoUnaryOp(
    name: String,
    val operator: Go.UnaryOp,
) : GoSupportCode(name) {
    override fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
        return Go.UnaryExpr(pos, operator, arguments[0].expr)
    }
}

internal class GoStrCat(name: String) : GoSupportCode(name) {
    override fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
        // StrCat can take variable number of arguments (from string interpolation).
        // Chain them as binary `+` operations: a + b + c + ...
        return arguments.map { it.expr }.reduce { acc, expr ->
            Go.BinaryExpr(pos, acc, Go.BinOp.Plus, expr)
        }
    }
}

internal class GoFmtSprint(name: String) : GoSupportCode(name) {
    override fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
        translator.needsImport("fmt")
        return Go.CallExpr(
            pos,
            fn = Go.SelectorExpr(pos, Go.Ident(pos, "fmt"), "Sprint"),
            args = arguments.map { it.expr },
        )
    }
}

internal class GoMathFunc(name: String, private val mathFuncName: String) : GoSupportCode(name) {
    override fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
        translator.needsImport("math")
        return Go.CallExpr(
            pos,
            fn = Go.SelectorExpr(pos, Go.Ident(pos, "math"), mathFuncName),
            args = arguments.map { it.expr },
        )
    }
}

internal class GoTemperCoreFunc(name: String, private val funcName: String) : GoSupportCode(name) {
    override fun inlineToGo(pos: Position, arguments: List<TypedArg<Go.Expr>>, translator: GoTranslator): Go.Expr {
        translator.needsImport("temper.systems/core/go")
        return Go.CallExpr(
            pos,
            fn = Go.SelectorExpr(pos, Go.Ident(pos, "tempercore"), funcName),
            args = arguments.map { it.expr },
        )
    }
}

@Suppress("MagicNumber")
private val supportCodeByOperatorId: Map<BuiltinOperatorId, GoSupportCode> = buildMap {
    // Arithmetic
    for (id in listOf(BuiltinOperatorId.PlusIntInt, BuiltinOperatorId.PlusIntInt64)) {
        put(id, GoInfixOp("PlusInt", Go.BinOp.Plus))
    }
    put(BuiltinOperatorId.PlusFltFlt, GoInfixOp("PlusFlt", Go.BinOp.Plus))
    for (id in listOf(BuiltinOperatorId.MinusIntInt, BuiltinOperatorId.MinusIntInt64)) {
        put(id, GoInfixOp("MinusInt", Go.BinOp.Minus))
    }
    put(BuiltinOperatorId.MinusFltFlt, GoInfixOp("MinusFlt", Go.BinOp.Minus))
    for (id in listOf(BuiltinOperatorId.TimesIntInt, BuiltinOperatorId.TimesIntInt64)) {
        put(id, GoInfixOp("TimesInt", Go.BinOp.Times))
    }
    put(BuiltinOperatorId.TimesFltFlt, GoInfixOp("TimesFlt", Go.BinOp.Times))
    for (id in listOf(
        BuiltinOperatorId.DivIntInt, BuiltinOperatorId.DivIntInt64,
        BuiltinOperatorId.DivIntIntSafe, BuiltinOperatorId.DivIntInt64Safe,
    )) {
        put(id, GoInfixOp("DivInt", Go.BinOp.Div))
    }
    put(BuiltinOperatorId.DivFltFlt, GoInfixOp("DivFlt", Go.BinOp.Div))
    for (id in listOf(
        BuiltinOperatorId.ModIntInt, BuiltinOperatorId.ModIntInt64,
        BuiltinOperatorId.ModIntIntSafe, BuiltinOperatorId.ModIntInt64Safe,
    )) {
        put(id, GoInfixOp("ModInt", Go.BinOp.Mod))
    }
    put(BuiltinOperatorId.ModFltFlt, GoMathFunc("ModFlt", "Mod"))
    put(BuiltinOperatorId.PowFltFlt, GoMathFunc("PowFlt", "Pow"))

    // Comparison
    for (id in listOf(
        BuiltinOperatorId.LtIntInt, BuiltinOperatorId.LtFltFlt,
        BuiltinOperatorId.LtStrStr, BuiltinOperatorId.LtGeneric,
    )) {
        put(id, GoInfixOp("Lt", Go.BinOp.Lt))
    }
    for (id in listOf(
        BuiltinOperatorId.LeIntInt, BuiltinOperatorId.LeFltFlt,
        BuiltinOperatorId.LeStrStr, BuiltinOperatorId.LeGeneric,
    )) {
        put(id, GoInfixOp("Le", Go.BinOp.Le))
    }
    for (id in listOf(
        BuiltinOperatorId.GtIntInt, BuiltinOperatorId.GtFltFlt,
        BuiltinOperatorId.GtStrStr, BuiltinOperatorId.GtGeneric,
    )) {
        put(id, GoInfixOp("Gt", Go.BinOp.Gt))
    }
    for (id in listOf(
        BuiltinOperatorId.GeIntInt, BuiltinOperatorId.GeFltFlt,
        BuiltinOperatorId.GeStrStr, BuiltinOperatorId.GeGeneric,
    )) {
        put(id, GoInfixOp("Ge", Go.BinOp.Ge))
    }
    for (id in listOf(
        BuiltinOperatorId.EqIntInt, BuiltinOperatorId.EqFltFlt,
        BuiltinOperatorId.EqStrStr, BuiltinOperatorId.EqGeneric,
    )) {
        put(id, GoInfixOp("Eq", Go.BinOp.Eq))
    }
    for (id in listOf(
        BuiltinOperatorId.NeIntInt, BuiltinOperatorId.NeFltFlt,
        BuiltinOperatorId.NeStrStr, BuiltinOperatorId.NeGeneric,
    )) {
        put(id, GoInfixOp("Ne", Go.BinOp.Ne))
    }

    // Unary
    for (id in listOf(BuiltinOperatorId.MinusInt, BuiltinOperatorId.MinusInt64)) {
        put(id, GoUnaryOp("NegInt", Go.UnaryOp.Neg))
    }
    put(BuiltinOperatorId.MinusFlt, GoUnaryOp("NegFlt", Go.UnaryOp.Neg))
    put(BuiltinOperatorId.BooleanNegation, GoUnaryOp("Not", Go.UnaryOp.Not))

    // Bitwise
    put(BuiltinOperatorId.BitwiseAnd, GoInfixOp("BitwiseAnd", Go.BinOp.BitwiseAnd))
    put(BuiltinOperatorId.BitwiseOr, GoInfixOp("BitwiseOr", Go.BinOp.BitwiseOr))

    // String
    put(BuiltinOperatorId.StrCat, GoStrCat("StrCat"))
}
