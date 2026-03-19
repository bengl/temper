package lang.temper.be.go

import lang.temper.be.Backend
import lang.temper.be.tmpl.SupportCode
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TypedArg
import lang.temper.common.Log
import lang.temper.common.MimeType
import lang.temper.format.toStringViaTokenSink
import lang.temper.log.LogSink
import lang.temper.log.MessageTemplate
import lang.temper.log.Position
import lang.temper.name.ImplicitsCodeLocation
import lang.temper.name.ResolvedName
import lang.temper.name.ResolvedParsedName
import lang.temper.type.WellKnownTypes
import lang.temper.value.TBoolean
import lang.temper.value.TFloat64
import lang.temper.value.TInt
import lang.temper.value.TInt64
import lang.temper.value.TNull
import lang.temper.value.TString
import lang.temper.value.TVoid

/**
 * Minimal TmpL-to-Go translator. Currently handles enough nodes for simple programs
 * like hello-world and fibonacci.
 */
class GoTranslator(
    internal val module: TmpL.Module,
    private val logSink: LogSink = LogSink.devNull,
) {
    private val imports = mutableSetOf<String>()
    private val initStmts = mutableListOf<Go.Stmt>()
    private val topLevelFuncs = mutableListOf<Go.FuncDecl>()
    private val supportCodeByName = mutableMapOf<ResolvedName, SupportCode>()

    internal fun needsImport(pkg: String) {
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

        // Build a Go.File with top-level functions + a main function.
        val pos = module.pos
        val mainFunc = Go.FuncDecl(
            pos,
            name = "main",
            receiver = null,
            params = emptyList(),
            results = emptyList(),
            body = Go.BlockStmt(pos, initStmts),
        )

        val allDecls: List<Go.Decl> = topLevelFuncs + listOf(mainFunc)

        val file = Go.File(
            pos,
            packageName = "main",
            imports = imports.sorted().map { Go.ImportSpec(pos, alias = null, path = it) },
            decls = allDecls,
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
            is TmpL.ModuleFunctionDeclaration -> processModuleFunctionDeclaration(topLevel)
            else -> logCannotTranslate(
                topLevel.pos,
                "Go backend: unsupported top-level node ${topLevel::class.simpleName}",
            )
        }
    }

    private fun processModuleInitBlock(block: TmpL.ModuleInitBlock) {
        processStatements(block.body.statements, initStmts)
    }

    private fun processModuleLevelDeclaration(decl: TmpL.ModuleLevelDeclaration) {
        // Skip console declarations.
        if (isConsoleDeclaration(decl)) return
        // Translate as a variable declaration in init.
        val stmt = translateLocalDeclaration(decl) ?: return
        initStmts.add(stmt)
    }

    private fun processModuleFunctionDeclaration(decl: TmpL.ModuleFunctionDeclaration) {
        val pos = decl.pos
        val nameText = nameToString(decl.name.name) ?: run {
            logCannotTranslate(pos, "Go backend: cannot resolve function name")
            return
        }

        // Translate parameters (skip `this` parameter if present).
        val params = decl.parameters.parameters.mapNotNull { formal ->
            // Skip `this` parameter — Go top-level functions have no receiver here.
            if (formal.name == decl.parameters.thisName) return@mapNotNull null
            val formalName = nameToString(formal.name.name) ?: return@mapNotNull null
            val formalType = translateAType(formal.type) ?: run {
                logCannotTranslate(pos, "Go backend: falling back to 'any' for parameter '$formalName'")
                Go.NamedType(pos, "any")
            }
            Go.Field(pos, name = formalName, type = formalType)
        }

        // Translate return type.
        val returnType = translateAType(decl.returnType)
        val results = if (returnType != null) listOf(returnType) else emptyList()

        // Translate body.
        val bodyStmts = mutableListOf<Go.Stmt>()
        processStatements(decl.body.statements, bodyStmts)

        val funcDecl = Go.FuncDecl(
            pos,
            name = nameText,
            receiver = null,
            params = params,
            results = results,
            body = Go.BlockStmt(pos, bodyStmts),
        )
        topLevelFuncs.add(funcDecl)
    }

    private fun translateAType(atype: TmpL.AType): Go.TypeExpr? {
        val pos = atype.pos
        val ot = atype.privOtOrNull ?: return null
        return translateType(ot, pos)
    }

    private fun translateType(type: TmpL.Type, pos: lang.temper.log.Position): Go.TypeExpr? {
        return when (type) {
            is TmpL.NominalType -> {
                val typeDef = type.typeName.sourceDefinition ?: return null
                when (typeDef) {
                    WellKnownTypes.intTypeDefinition -> Go.NamedType(pos, "int")
                    WellKnownTypes.booleanTypeDefinition -> Go.NamedType(pos, "bool")
                    WellKnownTypes.stringTypeDefinition -> Go.NamedType(pos, "string")
                    WellKnownTypes.float64TypeDefinition -> Go.NamedType(pos, "float64")
                    WellKnownTypes.voidTypeDefinition -> null // void -> no return type
                    else -> {
                        logCannotTranslate(pos, "Go backend: unsupported type ${typeDef.name}")
                        null
                    }
                }
            }
            else -> {
                logCannotTranslate(pos, "Go backend: unsupported type form ${type::class.simpleName}")
                null
            }
        }
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
            is TmpL.LocalDeclaration -> translateLocalDeclaration(statement)
            is TmpL.Assignment -> translateAssignment(statement)
            is TmpL.WhileStatement -> translateWhileStatement(statement)
            is TmpL.IfStatement -> translateIfStatement(statement)
            else -> {
                logCannotTranslate(
                    statement.pos,
                    "Go backend: unsupported statement ${statement::class.simpleName}",
                )
                null
            }
        }
    }

    private fun translateLocalDeclaration(decl: TmpL.ModuleOrLocalDeclaration): Go.Stmt? {
        val pos = decl.pos
        val nameText = nameToString(decl.name.name) ?: return null
        val initExpr = decl.init?.let { translateExpression(it) }
        // Use `:=` short variable declaration if we have an initializer (simpler for local vars).
        // Use `var` declaration if no initializer.
        return if (initExpr != null) {
            Go.AssignStmt(
                pos,
                lhs = listOf(Go.Ident(pos, nameText)),
                rhs = listOf(initExpr),
                op = Go.AssignOp.Define,
            )
        } else {
            val typeExpr = decl.type.privOtOrNull?.let { translateType(it, pos) }
            Go.DeclStmt(pos, Go.VarDecl(pos, nameText, typeExpr, null))
        }
    }

    private fun translateAssignment(statement: TmpL.Assignment): Go.Stmt? {
        val pos = statement.pos
        val nameText = nameToString(statement.left.name) ?: return null
        val right = statement.right as? TmpL.Expression ?: run {
            logCannotTranslate(pos, "Go backend: assignment RHS is not an expression")
            return null
        }
        val rightExpr = translateExpression(right) ?: return null
        return Go.AssignStmt(
            pos,
            lhs = listOf(Go.Ident(pos, nameText)),
            rhs = listOf(rightExpr),
            op = Go.AssignOp.Assign,
        )
    }

    private fun translateWhileStatement(statement: TmpL.WhileStatement): Go.Stmt? {
        val pos = statement.pos
        val cond = translateExpression(statement.test) ?: return null
        val bodyStmt = translateStatement(statement.body)
        val bodyBlock = when (bodyStmt) {
            is Go.BlockStmt -> bodyStmt
            null -> Go.BlockStmt(pos, emptyList())
            else -> Go.BlockStmt(pos, listOf(bodyStmt))
        }
        return Go.ForStmt(pos, init = null, cond = cond, post = null, body = bodyBlock)
    }

    private fun translateIfStatement(statement: TmpL.IfStatement): Go.Stmt? {
        val pos = statement.pos
        val cond = translateExpression(statement.test) ?: return null
        val consequentStmt = translateStatement(statement.consequent)
        val consequentBlock = when (consequentStmt) {
            is Go.BlockStmt -> consequentStmt
            null -> Go.BlockStmt(pos, emptyList())
            else -> Go.BlockStmt(pos, listOf(consequentStmt))
        }
        val elseStmt = statement.alternate?.let { alt ->
            when (val s = translateStatement(alt)) {
                is Go.ElseBranch -> s
                null -> null
                else -> Go.BlockStmt(pos, listOf(s))
            }
        }
        return Go.IfStmt(pos, init = null, cond = cond, body = consequentBlock, elseStmt = elseStmt)
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
            else -> {
                logCannotTranslate(expression.pos, "Go backend: unsupported expression ${expression::class.simpleName}")
                null
            }
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
                    translateExpression(actual as TmpL.Expression) ?: return null
                }
                Go.CallExpr(call.pos, fn = callee, args = args)
            }
            else -> {
                logCannotTranslate(call.pos, "Go backend: unsupported callable ${fn::class.simpleName}")
                null
            }
        }
    }

    private fun inlineGoSupportCode(call: TmpL.CallExpression, supportCode: GoSupportCode): Go.Expr? {
        val pos = call.pos
        val args = call.parameters.map { actual ->
            val typedExpr = actual as TmpL.Expression
            val expr = translateExpression(typedExpr)
            if (expr == null) {
                logCannotTranslate(pos, "Go backend: cannot translate argument in ${supportCode.connectedKey}")
                return null
            }
            TypedArg(expr, typedExpr.type)
        }
        return supportCode.inlineToGo(pos, args, this)
    }

    private fun translateCallable(callable: TmpL.Callable): Go.Expr? {
        return when (callable) {
            is TmpL.FnReference -> {
                val nameText = nameToString(callable.id.name) ?: return null
                Go.Ident(callable.pos, nameText)
            }
            else -> {
                logCannotTranslate(callable.pos, "Go backend: unsupported callable ${callable::class.simpleName}")
                null
            }
        }
    }

    internal fun nameToString(name: lang.temper.name.TemperName): String? {
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
            else -> {
                logCannotTranslate(pos, "Go backend: unsupported value type ${expression.value.typeTag}")
                null
            }
        }
    }

    private fun translateReference(expression: TmpL.Reference): Go.Expr? {
        val nameText = nameToString(expression.id.name) ?: return null
        return Go.Ident(expression.pos, nameText)
    }

    private fun logCannotTranslate(pos: Position, diagnostic: String) {
        logSink.log(
            level = Log.Error,
            template = MessageTemplate.CannotTranslate,
            pos = pos,
            values = listOf(diagnostic),
        )
    }

    companion object {
        internal fun goEscapeString(s: String): String {
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
