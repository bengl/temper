package lang.temper.be.go

import lang.temper.be.Backend
import lang.temper.be.tmpl.SupportCode
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLOperator
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
import lang.temper.name.Temporary
import lang.temper.type.WellKnownTypes
import lang.temper.value.TBoolean
import lang.temper.value.TFloat64
import lang.temper.value.TInt
import lang.temper.value.TInt64
import lang.temper.value.TNull
import lang.temper.value.TString
import lang.temper.value.TVoid
import lang.temper.value.failSymbol

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
    private val topLevelDecls = mutableListOf<Go.Decl>()
    private val supportCodeByName = mutableMapOf<ResolvedName, SupportCode>()

    /** Import paths that need an explicit alias because the package name differs from the path's last segment. */
    private val importAliases = mapOf(
        "temper.systems/core/go" to "tempercore",
    )

    /** Tracks variable names declared in nested scopes to avoid duplicate `:=`. */
    private val scopeStack = mutableListOf(mutableSetOf<String>())

    /** Check if a variable has been declared in the current or any enclosing scope. */
    private fun isDeclared(name: String): Boolean = scopeStack.any { name in it }

    /** Mark a variable as declared in the current (innermost) scope. */
    private fun markDeclared(name: String) { scopeStack.last().add(name) }

    /** Enter a new scope. */
    private fun pushScope() { scopeStack.add(mutableSetOf()) }

    /** Leave the current scope. */
    private fun popScope() { scopeStack.removeAt(scopeStack.lastIndex) }

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
        val cleanedInitStmts = removeUnusedDeclarations(initStmts)
        val mainFunc = Go.FuncDecl(
            pos,
            name = "main",
            receiver = null,
            params = emptyList(),
            results = emptyList(),
            body = Go.BlockStmt(pos, cleanedInitStmts),
        )

        val allDecls: List<Go.Decl> = topLevelDecls + listOf(mainFunc)

        val file = Go.File(
            pos,
            packageName = "main",
            imports = imports.sorted().map { importPath ->
                Go.ImportSpec(pos, alias = importAliases[importPath], path = importPath)
            },
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
            is TmpL.TypeDeclaration -> processTypeDeclaration(topLevel)
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
        pushScope()
        val bodyStmts = mutableListOf<Go.Stmt>()
        processStatements(decl.body.statements, bodyStmts)
        popScope()

        val funcDecl = Go.FuncDecl(
            pos,
            name = nameText,
            receiver = null,
            params = params,
            results = results,
            body = Go.BlockStmt(pos, bodyStmts),
        )
        topLevelDecls.add(funcDecl)
    }

    private fun processTypeDeclaration(decl: TmpL.TypeDeclaration) {
        when (decl.kind) {
            TmpL.TypeDeclarationKind.Class -> processTypeDeclarationClass(decl)
            else -> logCannotTranslate(
                decl.pos,
                "Go backend: unsupported type declaration kind ${decl.kind}",
            )
        }
    }

    private fun processTypeDeclarationClass(decl: TmpL.TypeDeclaration) {
        val pos = decl.pos
        val className = nameToString(decl.name.name) ?: return

        // Collect instance properties as struct fields.
        val properties = decl.members.filterIsInstance<TmpL.InstanceProperty>()
        val fields = properties.mapNotNull { prop ->
            val fieldName = prop.dotName.dotNameText
            val fieldType = translateAType(prop.type) ?: run {
                logCannotTranslate(pos, "Go backend: cannot translate field type for '$fieldName'")
                return@mapNotNull null
            }
            Go.Field(pos, name = fieldName, type = fieldType)
        }

        // Emit struct type declaration.
        val structDecl = Go.TypeDecl(
            pos,
            name = className,
            typeDef = Go.StructType(pos, fields),
        )
        topLevelDecls.add(structDecl)

        // Emit constructor function: func NewClassName(fields...) *ClassName { ... }
        val constructorParams = fields.map { f ->
            Go.Field(pos, name = f.name, type = f.type)
        }
        // Build constructor body: allocate struct, assign fields, return pointer.
        val constructorStmts = mutableListOf<Go.Stmt>()
        constructorStmts.add(
            Go.AssignStmt(
                pos,
                lhs = listOf(Go.Ident(pos, "result")),
                rhs = listOf(Go.CompositeLit(pos, type = Go.NamedType(pos, className), elts = emptyList())),
                op = Go.AssignOp.Define,
            ),
        )
        for (f in fields) {
            val fieldName = f.name ?: continue
            constructorStmts.add(
                Go.AssignStmt(
                    pos,
                    lhs = listOf(Go.SelectorExpr(pos, Go.Ident(pos, "result"), fieldName)),
                    rhs = listOf(Go.Ident(pos, fieldName)),
                    op = Go.AssignOp.Assign,
                ),
            )
        }
        constructorStmts.add(
            Go.ReturnStmt(pos, listOf(Go.AddressExpr(pos, Go.Ident(pos, "result")))),
        )
        val constructorBody = Go.BlockStmt(pos, constructorStmts)
        val constructorFunc = Go.FuncDecl(
            pos,
            name = "New$className",
            receiver = null,
            params = constructorParams,
            results = listOf(Go.PointerType(pos, Go.NamedType(pos, className))),
            body = constructorBody,
        )
        topLevelDecls.add(constructorFunc)

        // Emit methods.
        val methods = decl.members.filterIsInstance<TmpL.NormalMethod>()
        for (method in methods) {
            processInstanceMethod(method, className)
        }
    }

    private fun processInstanceMethod(method: TmpL.NormalMethod, className: String) {
        val pos = method.pos
        val methodName = method.dotName.dotNameText

        // Translate parameters (skip `this` parameter).
        val params = method.parameters.parameters.mapNotNull { formal ->
            if (formal.name == method.parameters.thisName) return@mapNotNull null
            val formalName = nameToString(formal.name.name) ?: return@mapNotNull null
            val formalType = translateAType(formal.type) ?: run {
                logCannotTranslate(pos, "Go backend: falling back to 'any' for parameter '$formalName'")
                Go.NamedType(pos, "any")
            }
            Go.Field(pos, name = formalName, type = formalType)
        }

        // Translate return type.
        val returnType = translateAType(method.returnType)
        val results = if (returnType != null) listOf(returnType) else emptyList()

        // Translate body.
        pushScope()
        val bodyStmts = mutableListOf<Go.Stmt>()
        method.body?.let { processStatements(it.statements, bodyStmts) }
        popScope()

        val funcDecl = Go.FuncDecl(
            pos,
            name = methodName,
            receiver = Go.Field(
                pos,
                name = "self",
                type = Go.PointerType(pos, Go.NamedType(pos, className)),
            ),
            params = params,
            results = results,
            body = Go.BlockStmt(pos, bodyStmts),
        )
        topLevelDecls.add(funcDecl)
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
                    WellKnownTypes.intTypeDefinition -> Go.NamedType(pos, "int32")
                    WellKnownTypes.int64TypeDefinition -> Go.NamedType(pos, "int64")
                    WellKnownTypes.booleanTypeDefinition -> Go.NamedType(pos, "bool")
                    WellKnownTypes.stringTypeDefinition -> Go.NamedType(pos, "string")
                    WellKnownTypes.float64TypeDefinition -> Go.NamedType(pos, "float64")
                    WellKnownTypes.voidTypeDefinition -> null // void -> no return type
                    else -> {
                        // User-defined type — use pointer to struct.
                        val typeName = nameToString(typeDef.name) ?: return null
                        Go.PointerType(pos, Go.NamedType(pos, typeName))
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
        var i = 0
        while (i < statements.size) {
            val statement = statements[i]
            when {
                // HandlerScope as standalone statement — peek at next statement for the if-check.
                statement is TmpL.HandlerScope -> {
                    val check = statements.getOrNull(i + 1)
                    translateHandlerScopeStandalone(statement, check)?.let { results.add(it) }
                    i += 2
                    continue
                }
                // Assignment with HandlerScope RHS — peek at next statement for the if-check.
                statement is TmpL.Assignment && statement.right is TmpL.HandlerScope -> {
                    val check = statements.getOrNull(i + 1)
                    translateHandlerScopeAssignment(statement, check)?.let { results.addAll(it) }
                    i += 2
                    continue
                }
                // Local function declarations — translate as top-level Go functions.
                statement is TmpL.LocalFunctionDeclaration -> {
                    processLocalFunctionDeclaration(statement)
                    i += 1
                    continue
                }
            }
            translateStatement(statement)?.let { results.add(it) }
            i += 1
        }
    }

    private fun translateStatement(statement: TmpL.Statement): Go.Stmt? {
        return when (statement) {
            is TmpL.ExpressionStatement -> translateExpressionStatement(statement)
            is TmpL.BlockStatement -> {
                pushScope()
                val stmts = mutableListOf<Go.Stmt>()
                processStatements(statement.statements, stmts)
                popScope()
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
            is TmpL.LabeledStatement -> translateLabeledStatement(statement)
            is TmpL.BreakStatement -> translateBreakStatement(statement)
            is TmpL.ContinueStatement -> translateContinueStatement(statement)
            is TmpL.ModuleInitFailed -> translateModuleInitFailed(statement)
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
        // Skip declarations marked with failSymbol — these are handler scope failure flags.
        if (decl.metadata.any { it.key.symbol == failSymbol }) return null
        val pos = decl.pos
        val nameText = nameToString(decl.name.name) ?: return null
        val initExpr = decl.init?.let { translateExpression(it) }
        val alreadyDeclared = isDeclared(nameText)
        markDeclared(nameText)
        // Use `:=` short variable declaration if we have an initializer and the variable is new.
        // Use `=` assignment if the variable was already declared in the same scope.
        // Use `var` declaration if no initializer.
        return if (initExpr != null) {
            Go.AssignStmt(
                pos,
                lhs = listOf(Go.Ident(pos, nameText)),
                rhs = listOf(initExpr),
                op = if (alreadyDeclared) Go.AssignOp.Assign else Go.AssignOp.Define,
            )
        } else {
            if (alreadyDeclared) {
                // Variable already declared; skip redundant declaration.
                null
            } else {
                val typeExpr = decl.type.privOtOrNull?.let { translateType(it, pos) }
                Go.DeclStmt(pos, Go.VarDecl(pos, nameText, typeExpr, null))
            }
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
        pushScope()
        val bodyStmt = translateStatement(statement.body)
        popScope()
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
        pushScope()
        val consequentStmt = translateStatement(statement.consequent)
        popScope()
        val consequentBlock = when (consequentStmt) {
            is Go.BlockStmt -> consequentStmt
            null -> Go.BlockStmt(pos, emptyList())
            else -> Go.BlockStmt(pos, listOf(consequentStmt))
        }
        val elseStmt = statement.alternate?.let { alt ->
            pushScope()
            val s = translateStatement(alt)
            popScope()
            when (s) {
                is Go.ElseBranch -> s
                null -> null
                else -> Go.BlockStmt(pos, listOf(s))
            }
        }
        return Go.IfStmt(pos, init = null, cond = cond, body = consequentBlock, elseStmt = elseStmt)
    }

    private fun translateLabeledStatement(statement: TmpL.LabeledStatement): Go.Stmt? {
        val pos = statement.pos
        val labelName = nameToString(statement.label.id.name) ?: return null
        pushScope()
        val innerStmt = translateStatement(statement.statement)
        popScope()
        innerStmt ?: return null

        // In Go, `break` only works with for/switch/select. If the inner statement is already
        // a ForStmt, just label it directly. Otherwise, wrap in a `for { ...; break }` loop
        // so that break statements targeting this label can exit the block.
        return if (innerStmt is Go.ForStmt) {
            Go.LabeledStmt(pos, labelName, innerStmt)
        } else {
            // Wrap in `label: for { body; break label }` — the body already has break
            // statements that exit, and we add a trailing break for fall-through.
            val bodyStmts = when (innerStmt) {
                is Go.BlockStmt -> innerStmt.stmts.toMutableList()
                else -> mutableListOf(innerStmt)
            }
            // Add a trailing break if the last statement isn't already a break or return.
            val lastStmt = bodyStmts.lastOrNull()
            if (lastStmt !is Go.BreakStmt && lastStmt !is Go.ReturnStmt) {
                bodyStmts.add(Go.BreakStmt(pos, labelName))
            }
            val forLoop = Go.ForStmt(
                pos,
                init = null,
                cond = null,
                post = null,
                body = Go.BlockStmt(pos, bodyStmts),
            )
            Go.LabeledStmt(pos, labelName, forLoop)
        }
    }

    private fun translateBreakStatement(statement: TmpL.BreakStatement): Go.Stmt {
        val label = statement.label?.let { nameToString(it.id.name) }
        return Go.BreakStmt(statement.pos, label)
    }

    private fun translateContinueStatement(statement: TmpL.ContinueStatement): Go.Stmt {
        val label = statement.label?.let { nameToString(it.id.name) }
        return Go.ContinueStmt(statement.pos, label)
    }

    private fun translateModuleInitFailed(statement: TmpL.ModuleInitFailed): Go.Stmt {
        val pos = statement.pos
        return Go.ExprStmt(
            pos,
            Go.CallExpr(
                pos,
                fn = Go.Ident(pos, "panic"),
                args = listOf(Go.BasicLit(pos, Go.BasicLitKind.String, "\"module init failed\"")),
            ),
        )
    }

    /**
     * Translate a standalone [TmpL.HandlerScope] (not assigned to anything).
     * The handler scope evaluates [TmpL.HandlerScope.handled] and the next statement [check]
     * is an if-statement testing the failure flag.
     */
    private fun translateHandlerScopeStandalone(
        handlerScope: TmpL.HandlerScope,
        check: TmpL.Statement?,
    ): Go.Stmt? {
        val pos = handlerScope.pos
        val handled = handlerScope.handled as? TmpL.Expression ?: return null
        val handledExpr = translateExpression(handled) ?: return null
        // Generate: handledExpr (discard result)
        val exprStmt = Go.ExprStmt(pos, handledExpr)
        if (check is TmpL.IfStatement) {
            // The if-check tests the fail flag. Translate the consequent.
            val consequent = translateStatement(check.consequent)
            val consequentBlock = when (consequent) {
                is Go.BlockStmt -> consequent
                null -> Go.BlockStmt(pos, emptyList())
                else -> Go.BlockStmt(pos, listOf(consequent))
            }
            // For standalone handler scope, we just need to translate the check.
            // The `handled` is a side-effecting expression — execute it as a statement.
            val ifStmt = Go.IfStmt(
                check.pos,
                init = null,
                cond = translateExpression(check.test) ?: return null,
                body = consequentBlock,
                elseStmt = null,
            )
            return Go.BlockStmt(pos, listOf(exprStmt, ifStmt))
        }
        return exprStmt
    }

    /**
     * Check if a [TmpL.Expression] will produce a fallible (multi-value) Go call.
     * Returns true if the expression is a call to a [GoFallibleTemperCoreFunc].
     */
    private fun isFallibleGoCall(expression: TmpL.Expression): Boolean {
        if (expression !is TmpL.CallExpression) return false
        val fn = expression.fn as? TmpL.FnReference ?: return false
        val supportCode = supportCodeByName[fn.id.name]
        return supportCode is GoSupportCode && supportCode.isFallible
    }

    /**
     * Translate an [TmpL.Assignment] where the RHS is a [TmpL.HandlerScope].
     *
     * For fallible operations (that return `(value, bool)` in Go):
     * ```go
     * result, failed := tempercore.ParseInt32(s, radix)
     * if failed {
     *     panic("module init failed")  // or break label, etc.
     * }
     * ```
     *
     * For non-fallible operations (e.g., safe div/mod that Go handles natively):
     * ```go
     * result := expr
     * ```
     * The failure check is skipped since Go handles the edge cases natively.
     */
    private fun translateHandlerScopeAssignment(
        assignment: TmpL.Assignment,
        check: TmpL.Statement?,
    ): List<Go.Stmt>? {
        val pos = assignment.pos
        val handlerScope = assignment.right as TmpL.HandlerScope
        val handled = handlerScope.handled as? TmpL.Expression ?: return null
        val handledExpr = translateExpression(handled) ?: return null
        val resultName = nameToString(assignment.left.name) ?: return null

        val stmts = mutableListOf<Go.Stmt>()

        if (isFallibleGoCall(handled)) {
            // The Go function returns (value, bool). Generate multi-value assignment.
            val failedName = nameToString(handlerScope.failed.name) ?: return null
            val resultAlreadyDeclared = isDeclared(resultName)
            markDeclared(resultName)
            markDeclared(failedName)

            if (resultAlreadyDeclared) {
                // Variable already declared — declare fail var separately and use `=`.
                stmts.add(
                    Go.DeclStmt(pos, Go.VarDecl(pos, failedName, Go.NamedType(pos, "bool"), null)),
                )
                stmts.add(
                    Go.AssignStmt(
                        pos,
                        lhs = listOf(Go.Ident(pos, resultName), Go.Ident(pos, failedName)),
                        rhs = listOf(handledExpr),
                        op = Go.AssignOp.Assign,
                    ),
                )
            } else {
                stmts.add(
                    Go.AssignStmt(
                        pos,
                        lhs = listOf(Go.Ident(pos, resultName), Go.Ident(pos, failedName)),
                        rhs = listOf(handledExpr),
                        op = Go.AssignOp.Define,
                    ),
                )
            }

            // Generate the if-check from the next statement.
            if (check is TmpL.IfStatement) {
                val cond = translateExpression(check.test) ?: return stmts
                pushScope()
                val consequent = translateStatement(check.consequent)
                popScope()
                val consequentBlock = when (consequent) {
                    is Go.BlockStmt -> consequent
                    null -> Go.BlockStmt(pos, emptyList())
                    else -> Go.BlockStmt(pos, listOf(consequent))
                }
                val elseStmt = check.alternate?.let { alt ->
                    pushScope()
                    val s = translateStatement(alt)
                    popScope()
                    when (s) {
                        is Go.ElseBranch -> s
                        null -> null
                        else -> Go.BlockStmt(pos, listOf(s))
                    }
                }
                stmts.add(
                    Go.IfStmt(check.pos, init = null, cond = cond, body = consequentBlock, elseStmt = elseStmt),
                )
            }
        } else {
            // Non-fallible operation — Go handles the edge cases natively.
            // Just assign the result directly and skip the failure check.
            val alreadyDeclared = isDeclared(resultName)
            markDeclared(resultName)
            stmts.add(
                Go.AssignStmt(
                    pos,
                    lhs = listOf(Go.Ident(pos, resultName)),
                    rhs = listOf(handledExpr),
                    op = if (alreadyDeclared) Go.AssignOp.Assign else Go.AssignOp.Define,
                ),
            )
        }

        return stmts
    }

    /**
     * Translate a [TmpL.LocalFunctionDeclaration] as a top-level Go function.
     * Go doesn't have nested named function declarations, so we hoist them to top level.
     */
    private fun processLocalFunctionDeclaration(decl: TmpL.LocalFunctionDeclaration) {
        val pos = decl.pos
        val nameText = nameToString(decl.name.name) ?: run {
            logCannotTranslate(pos, "Go backend: cannot resolve local function name")
            return
        }

        // Translate parameters (skip `this` parameter if present).
        val params = decl.parameters.parameters.mapNotNull { formal ->
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
        pushScope()
        val bodyStmts = mutableListOf<Go.Stmt>()
        processStatements(decl.body.statements, bodyStmts)
        popScope()

        val funcDecl = Go.FuncDecl(
            pos,
            name = nameText,
            receiver = null,
            params = params,
            results = results,
            body = Go.BlockStmt(pos, bodyStmts),
        )
        topLevelDecls.add(funcDecl)
    }

    private fun translateExpressionStatement(statement: TmpL.ExpressionStatement): Go.Stmt? {
        val expr = translateExpression(statement.expression)
        if (expr == null) {
            val exprType = statement.expression::class.simpleName
            logCannotTranslate(statement.pos, "Go backend: cannot translate expression $exprType")
            return null
        }
        return Go.ExprStmt(statement.pos, expr)
    }

    private fun translateExpression(expression: TmpL.Expression): Go.Expr? {
        return when (expression) {
            is TmpL.CallExpression -> translateCallExpression(expression)
            is TmpL.ValueReference -> translateValueReference(expression)
            is TmpL.Reference -> translateReference(expression)
            is TmpL.InfixOperation -> translateInfixOperation(expression)
            is TmpL.This -> Go.Ident(expression.pos, "self")
            is TmpL.GetBackedProperty -> translateGetProperty(expression)
            is TmpL.GetAbstractProperty -> translateGetProperty(expression)
            else -> {
                logCannotTranslate(expression.pos, "Go backend: unsupported expression ${expression::class.simpleName}")
                null
            }
        }
    }

    private fun translateGetProperty(expression: TmpL.GetProperty): Go.Expr? {
        val pos = expression.pos
        val subject = when (val subj = expression.subject) {
            is TmpL.Expression -> translateExpression(subj)
            else -> {
                logCannotTranslate(pos, "Go backend: unsupported property subject ${subj::class.simpleName}")
                null
            }
        } ?: return null
        val propName = translatePropertyId(expression.property) ?: return null
        return Go.SelectorExpr(pos, subject, propName)
    }

    private fun translatePropertyId(property: TmpL.PropertyId): String? {
        return when (property) {
            is TmpL.InternalPropertyId -> nameToString(property.name.name)
            is TmpL.ExternalPropertyId -> property.name.dotNameText
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
            is TmpL.ConstructorReference -> {
                val typeName = nameToString(fn.typeName.sourceDefinition.name) ?: return null
                val args = call.parameters.map { actual ->
                    translateExpression(actual as TmpL.Expression) ?: return null
                }
                Go.CallExpr(call.pos, fn = Go.Ident(call.pos, "New$typeName"), args = args)
            }
            is TmpL.MethodReference -> {
                val subject = translateExpression(fn.subject as TmpL.Expression) ?: return null
                val methodName = fn.methodName.dotNameText
                val args = call.parameters.map { actual ->
                    translateExpression(actual as TmpL.Expression) ?: return null
                }
                Go.CallExpr(
                    call.pos,
                    fn = Go.SelectorExpr(call.pos, subject, methodName),
                    args = args,
                )
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
        val raw = when (name) {
            is ResolvedParsedName -> name.baseName.nameText
            is Temporary -> "${name.nameHint}_${name.uid}"
            else -> name.rawDiagnostic
        }
        return raw?.let { escapeGoKeyword(it) }
    }

    companion object {
        private val goKeywords = setOf(
            "break", "case", "chan", "const", "continue",
            "default", "defer", "else", "fallthrough", "for",
            "func", "go", "goto", "if", "import",
            "interface", "map", "package", "range", "return",
            "select", "struct", "switch", "type", "var",
        )

        /** Predeclared identifiers that conflict with Go types when used as variable names. */
        private val goBuiltinTypes = setOf(
            "bool", "byte", "complex64", "complex128", "error",
            "float32", "float64", "int", "int8", "int16", "int32", "int64",
            "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
        )

        internal fun escapeGoKeyword(name: String): String =
            if (name in goKeywords || name in goBuiltinTypes) "${name}_" else name

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

    private fun translateValueReference(expression: TmpL.ValueReference): Go.Expr? {
        val pos = expression.pos
        return when (expression.value.typeTag) {
            is TString -> {
                val value = TString.unpack(expression.value)
                Go.BasicLit(pos, Go.BasicLitKind.String, "\"${goEscapeString(value)}\"")
            }
            TInt -> {
                val value = TInt.unpack(expression.value)
                // Wrap in int32() to ensure the literal is typed as int32, not Go's default int.
                Go.CallExpr(
                    pos,
                    fn = Go.Ident(pos, "int32"),
                    args = listOf(Go.BasicLit(pos, Go.BasicLitKind.Int, value.toString())),
                )
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

    private fun translateInfixOperation(expression: TmpL.InfixOperation): Go.Expr? {
        val pos = expression.pos
        val left = translateExpression(expression.left) ?: return null
        val right = translateExpression(expression.right) ?: return null
        val op = when (expression.op.tmpLOperator) {
            TmpLOperator.AmpAmp -> Go.BinOp.And
            TmpLOperator.BarBar -> Go.BinOp.Or
            else -> {
                logCannotTranslate(pos, "Go backend: unsupported infix operator ${expression.op.tmpLOperator}")
                return null
            }
        }
        return Go.BinaryExpr(pos, left, op, right)
    }

    private fun logCannotTranslate(pos: Position, diagnostic: String) {
        logSink.log(
            level = Log.Error,
            template = MessageTemplate.CannotTranslate,
            pos = pos,
            values = listOf(diagnostic),
        )
    }

    /**
     * Remove variable declarations whose names are never referenced elsewhere in the statement list.
     * Go requires all declared variables to be used; TmpL may emit declarations for constants that
     * get inlined at their use sites.
     */
    private fun removeUnusedDeclarations(stmts: List<Go.Stmt>): List<Go.Stmt> {
        // Collect all identifier names referenced in expressions (not on the LHS of declarations).
        val referenced = mutableSetOf<String>()
        for (stmt in stmts) {
            collectReferencedIdents(stmt, referenced, topLevel = true)
        }
        // Filter out `:=` declarations where the LHS name is never referenced.
        return stmts.filter { stmt ->
            when {
                stmt is Go.AssignStmt && stmt.op == Go.AssignOp.Define -> {
                    val name = (stmt.lhs.singleOrNull() as? Go.Ident)?.name
                    name == null || name in referenced
                }
                stmt is Go.DeclStmt -> {
                    stmt.decl.name in referenced
                }
                else -> true
            }
        }
    }

    private fun collectReferencedIdents(node: Go.Node, out: MutableSet<String>, topLevel: Boolean = false) {
        when (node) {
            is Go.Ident -> out.add(node.name)
            is Go.AssignStmt -> {
                // For define statements at the top level, only collect from RHS (not LHS).
                // For nested assigns and non-define assigns, collect from both.
                if (topLevel && node.op == Go.AssignOp.Define) {
                    node.rhs.forEach { collectReferencedIdents(it, out) }
                } else {
                    node.lhs.forEach { collectReferencedIdents(it, out) }
                    node.rhs.forEach { collectReferencedIdents(it, out) }
                }
            }
            is Go.DeclStmt -> {
                // Don't count the declaration name itself as a reference.
                node.decl.type?.let { collectReferencedIdents(it, out) }
                node.decl.init?.let { collectReferencedIdents(it, out) }
            }
            is Go.BlockStmt -> node.stmts.forEach { collectReferencedIdents(it, out) }
            is Go.ExprStmt -> collectReferencedIdents(node.expr, out)
            is Go.ReturnStmt -> node.results.forEach { collectReferencedIdents(it, out) }
            is Go.IfStmt -> {
                node.init?.let { collectReferencedIdents(it, out) }
                collectReferencedIdents(node.cond, out)
                collectReferencedIdents(node.body, out)
                (node.elseStmt as? Go.Node)?.let { collectReferencedIdents(it, out) }
            }
            is Go.ForStmt -> {
                node.init?.let { collectReferencedIdents(it, out) }
                node.cond?.let { collectReferencedIdents(it, out) }
                node.post?.let { collectReferencedIdents(it, out) }
                collectReferencedIdents(node.body, out)
            }
            is Go.LabeledStmt -> collectReferencedIdents(node.stmt, out)
            is Go.CallExpr -> {
                collectReferencedIdents(node.fn, out)
                node.args.forEach { collectReferencedIdents(it, out) }
            }
            is Go.SelectorExpr -> collectReferencedIdents(node.x, out)
            is Go.BinaryExpr -> {
                collectReferencedIdents(node.x, out)
                collectReferencedIdents(node.y, out)
            }
            is Go.UnaryExpr -> collectReferencedIdents(node.x, out)
            is Go.IndexExpr -> {
                collectReferencedIdents(node.x, out)
                collectReferencedIdents(node.index, out)
            }
            is Go.CompositeLit -> {
                node.type?.let { collectReferencedIdents(it, out) }
                node.elts.forEach { collectReferencedIdents(it, out) }
            }
            is Go.StarExpr -> collectReferencedIdents(node.x, out)
            is Go.AddressExpr -> collectReferencedIdents(node.x, out)
            is Go.SliceExpr -> {
                collectReferencedIdents(node.x, out)
                node.low?.let { collectReferencedIdents(it, out) }
                node.high?.let { collectReferencedIdents(it, out) }
            }
            is Go.TypeAssertExpr -> {
                collectReferencedIdents(node.x, out)
                collectReferencedIdents(node.assertType, out)
            }
            is Go.FuncLit -> collectReferencedIdents(node.body, out)
            is Go.KeyValueExpr -> {
                collectReferencedIdents(node.key, out)
                collectReferencedIdents(node.value, out)
            }
            // Leaf nodes or types that don't contain ident references.
            is Go.BasicLit, is Go.BreakStmt, is Go.ContinueStmt -> {}
            is Go.NamedType, is Go.PointerType, is Go.SliceType,
            is Go.MapType, is Go.QualifiedType, is Go.IndexTypeExpr,
            is Go.FuncType, is Go.StructType, is Go.InterfaceType,
            -> {}
            else -> {} // Catch-all for other node types.
        }
    }
}
