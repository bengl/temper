package lang.temper.be.go

import lang.temper.be.TargetLanguageTypeName
import lang.temper.be.tmpl.BubbleBranchStrategy
import lang.temper.be.tmpl.CoroutineStrategy
import lang.temper.be.tmpl.FunctionTypeStrategy
import lang.temper.be.tmpl.FunctionSupportCode
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
    ): SupportCode? = null

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
        translator: GoTranslator,
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
