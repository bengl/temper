package lang.temper.be.go

import lang.temper.be.Backend
import lang.temper.be.tmpl.SupportCode
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TypedArg
import lang.temper.common.MimeType
import lang.temper.format.toStringViaTokenSink
import lang.temper.name.ImplicitsCodeLocation
import lang.temper.name.ResolvedName
import lang.temper.name.ResolvedParsedName
import lang.temper.value.TBoolean
import lang.temper.value.TFloat64
import lang.temper.value.TInt
import lang.temper.value.TInt64
import lang.temper.value.TNull
import lang.temper.value.TString
import lang.temper.value.TVoid

/**
 * Minimal TmpL-to-Go translator. Currently handles enough nodes for simple programs
 * like hello-world.
 */
class GoTranslator(
    val module: TmpL.Module,
) {
    private val imports = mutableSetOf<String>()
    private val initStmts = mutableListOf<Go.Stmt>()
    private val supportCodeByName = mutableMapOf<ResolvedName, SupportCode>()

    fun needsImport(pkg: String) {
        imports.add(pkg)
    }

    fun translateModule(): Backend.MetadataFileSpecification {
        // First pass: collect support code declarations.
        for (topLevel in module.topLevels) {
            if (topLevel is TmpL.SupportCodeDeclaration) {
                supportCodeByName[topLevel.name.name] = topLevel.init.supportCode
            }
        }

        // Second pass: process top levels.
        for (topLevel in module.topLevels) {
            processTopLevel(topLevel)
        }

        // Build a Go.File with a main function containing init stmts.
        val pos = module.pos
        val mainFunc = Go.FuncDecl(
            pos,
            name = "main",
            receiver = null,
            params = emptyList(),
            results = emptyList(),
            body = Go.BlockStmt(pos, initStmts),
        )

        val file = Go.File(
            pos,
            packageName = "main",
            imports = imports.sorted().map { Go.ImportSpec(pos, alias = null, path = it) },
            decls = listOf(mainFunc),
        )

        // Render to string.
        val content = toStringViaTokenSink(singleLine = false) { sink ->
            GoRenderer.render(file, sink)
        }

        return Backend.MetadataFileSpecification(
            path = module.codeLocation.outputPath,
            mimeType = MimeType("text", "go"),
            content = content,
        )
    }

    private fun processTopLevel(topLevel: TmpL.TopLevel) {
        when (topLevel) {
            is TmpL.ModuleInitBlock -> processModuleInitBlock(topLevel)
            is TmpL.ModuleLevelDeclaration -> processModuleLevelDeclaration(topLevel)
            is TmpL.SupportCodeDeclaration -> {} // Already collected in first pass
            is TmpL.ModuleFunctionDeclaration -> {} // Not needed for hello-world yet
            else -> {} // Skip other top levels
        }
    }

    private fun processModuleInitBlock(block: TmpL.ModuleInitBlock) {
        processStatements(block.body.statements, initStmts)
    }

    private fun processModuleLevelDeclaration(decl: TmpL.ModuleLevelDeclaration) {
        // Skip console declarations.
        if (isConsoleDeclaration(decl)) return
        // Skip other module-level declarations for now.
    }

    private fun isConsoleDeclaration(decl: TmpL.ModuleLevelDeclaration): Boolean {
        val nominalType = decl.type.ot as? TmpL.NominalType ?: return false
        val typeDef = nominalType.typeName.sourceDefinition ?: return false
        if (typeDef.sourceLocation === ImplicitsCodeLocation) {
            val baseName = (typeDef.name as? ResolvedParsedName)?.baseName?.nameText
            if (baseName == "Console" || baseName == "GlobalConsole") return true
        }
        return false
    }

    private fun processStatements(statements: List<TmpL.Statement>, results: MutableList<Go.Stmt>) {
        for (statement in statements) {
            translateStatement(statement)?.let { results.add(it) }
        }
    }

    private fun translateStatement(statement: TmpL.Statement): Go.Stmt? {
        return when (statement) {
            is TmpL.ExpressionStatement -> translateExpressionStatement(statement)
            is TmpL.BlockStatement -> {
                val stmts = mutableListOf<Go.Stmt>()
                processStatements(statement.statements, stmts)
                Go.BlockStmt(statement.pos, stmts)
            }
            is TmpL.ReturnStatement -> {
                val expr = statement.expression?.let { translateExpression(it) }
                Go.ReturnStmt(statement.pos, if (expr != null) listOf(expr) else emptyList())
            }
            else -> null // Skip unhandled statements
        }
    }

    private fun translateExpressionStatement(statement: TmpL.ExpressionStatement): Go.Stmt? {
        val expr = translateExpression(statement.expression) ?: return null
        return Go.ExprStmt(statement.pos, expr)
    }

    private fun translateExpression(expression: TmpL.Expression): Go.Expr? {
        return when (expression) {
            is TmpL.CallExpression -> translateCallExpression(expression)
            is TmpL.ValueReference -> translateValueReference(expression)
            is TmpL.Reference -> translateReference(expression)
            else -> null // Skip unhandled expressions
        }
    }

    private fun translateCallExpression(call: TmpL.CallExpression): Go.Expr? {
        val fn = call.fn

        // Check if fn references a Go support code.
        if (fn is TmpL.FnReference) {
            val supportCode = supportCodeByName[fn.id.name]
            if (supportCode is GoSupportCode) {
                return inlineGoSupportCode(call, supportCode)
            }
        }

        // Regular call.
        return when (fn) {
            is TmpL.FnReference -> {
                val callee = translateCallable(fn) ?: return null
                val args = call.parameters.map { actual ->
                    translateExpression(actual as TmpL.Expression) ?: Go.Ident(call.pos, "_")
                }
                Go.CallExpr(call.pos, fn = callee, args = args)
            }
            else -> null
        }
    }

    private fun inlineGoSupportCode(call: TmpL.CallExpression, supportCode: GoSupportCode): Go.Expr? {
        val pos = call.pos
        val args = call.parameters.map { actual ->
            val expr = translateExpression(actual as TmpL.Expression) ?: Go.Ident(pos, "_")
            TypedArg(expr, (actual as TmpL.Expression).type)
        }
        return when (supportCode) {
            is GoConsoleLog -> supportCode.inlineToGo(pos, args, this)
            is GoGetConsole -> supportCode.inlineToGo(pos, this)
            else -> null
        }
    }

    private fun translateCallable(callable: TmpL.Callable): Go.Expr? {
        return when (callable) {
            is TmpL.FnReference -> {
                val nameText = nameToString(callable.id.name) ?: return null
                Go.Ident(callable.pos, nameText)
            }
            else -> null
        }
    }

    private fun nameToString(name: lang.temper.name.TemperName): String? {
        return when (name) {
            is ResolvedParsedName -> name.baseName.nameText
            else -> name.rawDiagnostic
        }
    }

    private fun translateValueReference(expression: TmpL.ValueReference): Go.Expr? {
        val pos = expression.pos
        return when (expression.value.typeTag) {
            is TString -> {
                val value = TString.unpack(expression.value)
                Go.BasicLit(pos, Go.BasicLitKind.String, "\"${goEscapeString(value)}\"")
            }
            TInt -> {
                val value = TInt.unpack(expression.value)
                Go.BasicLit(pos, Go.BasicLitKind.Int, value.toString())
            }
            TInt64 -> {
                val value = TInt64.unpack(expression.value)
                Go.BasicLit(pos, Go.BasicLitKind.Int, value.toString())
            }
            TFloat64 -> {
                val value = TFloat64.unpack(expression.value)
                Go.BasicLit(pos, Go.BasicLitKind.Float, value.toString())
            }
            TBoolean -> {
                val value = TBoolean.unpack(expression.value)
                Go.Ident(pos, value.toString())
            }
            TNull -> Go.Ident(pos, "nil")
            TVoid -> null
            else -> null
        }
    }

    private fun translateReference(expression: TmpL.Reference): Go.Expr? {
        val nameText = nameToString(expression.id.name) ?: return null
        return Go.Ident(expression.pos, nameText)
    }

    companion object {
        private fun goEscapeString(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
