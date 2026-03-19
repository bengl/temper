@file:Suppress("Wrapping")

package lang.temper.be.go

import lang.temper.format.OutputToken
import lang.temper.format.OutputTokenType
import lang.temper.format.TokenSink

object GoRenderer {

    fun render(node: Go.Node, sink: TokenSink) {
        when (node) {
            is Go.File -> renderFile(node, sink)
            is Go.FuncDecl -> renderFuncDecl(node, sink)
            is Go.TypeDecl -> renderTypeDecl(node, sink)
            is Go.VarDecl -> renderVarDecl(node, sink)
            is Go.BlockStmt -> renderBlockStmt(node, sink)
            is Go.ReturnStmt -> renderReturnStmt(node, sink)
            is Go.IfStmt -> renderIfStmt(node, sink)
            is Go.AssignStmt -> renderAssignStmt(node, sink)
            is Go.ExprStmt -> { renderExpr(node.expr, sink); sink.endLine() }
            is Go.DeclStmt -> renderVarDecl(node.decl, sink)
            is Go.ForStmt -> renderForStmt(node, sink)
            is Go.Ident -> sink.name(node.name)
            is Go.BasicLit -> renderBasicLit(node, sink)
            is Go.SelectorExpr -> { renderExpr(node.x, sink); sink.punct("."); sink.name(node.sel) }
            is Go.CallExpr -> renderCallExpr(node, sink)
            is Go.IndexExpr -> {
                renderExpr(node.x, sink); sink.bracket("["); renderExpr(node.index, sink); sink.bracket("]")
            }
            is Go.BinaryExpr -> { renderExpr(node.x, sink); sink.infixOp(node.op.symbol); renderExpr(node.y, sink) }
            is Go.UnaryExpr -> { sink.prefixOp(node.op.symbol); renderExpr(node.x, sink) }
            is Go.CompositeLit -> renderCompositeLit(node, sink)
            is Go.StarExpr -> { sink.punct("*"); renderExpr(node.x, sink) }
            is Go.AddressExpr -> { sink.punct("&"); renderExpr(node.x, sink) }
            is Go.SliceExpr -> renderSliceExpr(node, sink)
            is Go.TypeAssertExpr -> {
                renderExpr(node.x, sink); sink.punct(".("); renderTypeExpr(node.assertType, sink); sink.bracket(")")
            }
            is Go.FuncLit -> renderFuncLit(node, sink)
            is Go.KeyValueExpr -> {
                renderExpr(node.key, sink); sink.punct(":"); sink.ws(); renderExpr(node.value, sink)
            }
            is Go.NamedType -> sink.name(node.name)
            is Go.PointerType -> { sink.punct("*"); renderTypeExpr(node.base, sink) }
            is Go.SliceType -> { sink.punct("[]"); renderTypeExpr(node.elem, sink) }
            is Go.MapType -> {
                sink.keyword("map")
                sink.bracket("["); renderTypeExpr(node.key, sink); sink.bracket("]")
                renderTypeExpr(node.value, sink)
            }
            is Go.QualifiedType -> { sink.name(node.pkg); sink.punct("."); sink.name(node.name) }
            is Go.IndexTypeExpr -> renderIndexTypeExpr(node, sink)
            is Go.FuncType -> renderFuncType(node, sink)
            is Go.StructType -> renderStructType(node, sink)
            is Go.InterfaceType -> renderInterfaceType(node, sink)
            is Go.ImportSpec -> renderImportSpec(node, sink)
            is Go.Field -> renderField(node, sink)
            is Go.InterfaceMethod -> renderInterfaceMethod(node, sink)
        }
    }

    private fun renderFile(file: Go.File, sink: TokenSink) {
        sink.keyword("package"); sink.ws(); sink.name(file.packageName); sink.endLine(); sink.endLine()
        if (file.imports.isNotEmpty()) {
            sink.keyword("import"); sink.ws(); sink.bracket("("); sink.endLine()
            for (imp in file.imports) { renderImportSpec(imp, sink); sink.endLine() }
            sink.bracket(")"); sink.endLine(); sink.endLine()
        }
        for (decl in file.decls) {
            render(decl, sink)
            sink.endLine()
        }
    }

    private fun renderFuncDecl(fn: Go.FuncDecl, sink: TokenSink) {
        sink.keyword("func")
        fn.receiver?.let { recv ->
            sink.ws(); sink.bracket("("); renderField(recv, sink); sink.bracket(")")
        }
        sink.ws(); sink.name(fn.name)
        sink.bracket("(")
        fn.params.forEachIndexed { i, p ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderField(p, sink)
        }
        sink.bracket(")")
        when {
            fn.results.isEmpty() -> {}
            fn.results.size == 1 -> { sink.ws(); renderTypeExpr(fn.results[0], sink) }
            else -> {
                sink.ws(); sink.bracket("(")
                fn.results.forEachIndexed { i, r ->
                    if (i > 0) { sink.punct(","); sink.ws() }
                    renderTypeExpr(r, sink)
                }
                sink.bracket(")")
            }
        }
        sink.ws(); renderBlockStmt(fn.body, sink); sink.endLine()
    }

    private fun renderTypeDecl(decl: Go.TypeDecl, sink: TokenSink) {
        sink.keyword("type"); sink.ws(); sink.name(decl.name); sink.ws()
        render(decl.typeDef, sink); sink.endLine()
    }

    private fun renderVarDecl(decl: Go.VarDecl, sink: TokenSink) {
        sink.keyword("var"); sink.ws(); sink.name(decl.name)
        decl.type?.let { sink.ws(); renderTypeExpr(it, sink) }
        decl.init?.let { sink.ws(); sink.punct("="); sink.ws(); renderExpr(it, sink) }
        sink.endLine()
    }

    private fun renderForStmt(stmt: Go.ForStmt, sink: TokenSink) {
        sink.keyword("for")
        // While-style: `for cond { ... }`
        if (stmt.init == null && stmt.post == null) {
            stmt.cond?.let { sink.ws(); renderExpr(it, sink) }
        } else {
            // Three-part style: `for init; cond; post { ... }`
            // We render expressions directly to avoid trailing newlines from statement renderers.
            sink.ws()
            stmt.init?.let { renderForClauseStmt(it, sink) }
            sink.punct(";"); sink.ws()
            stmt.cond?.let { renderExpr(it, sink) }
            sink.punct(";"); sink.ws()
            stmt.post?.let { renderForClauseStmt(it, sink) }
        }
        sink.ws(); renderBlockStmt(stmt.body, sink); sink.endLine()
    }

    /** Renders a for-clause statement (init or post) without a trailing newline. */
    private fun renderForClauseStmt(stmt: Go.Stmt, sink: TokenSink) {
        when (stmt) {
            is Go.AssignStmt -> {
                stmt.lhs.forEachIndexed { i, e ->
                    if (i > 0) { sink.punct(","); sink.ws() }
                    renderExpr(e, sink)
                }
                sink.ws()
                when (stmt.op) {
                    Go.AssignOp.Assign -> sink.punct("=")
                    Go.AssignOp.Define -> sink.punct(":=")
                }
                sink.ws()
                stmt.rhs.forEachIndexed { i, e ->
                    if (i > 0) { sink.punct(","); sink.ws() }
                    renderExpr(e, sink)
                }
            }
            is Go.ExprStmt -> renderExpr(stmt.expr, sink)
            else -> render(stmt, sink)
        }
    }

    private fun renderBlockStmt(block: Go.BlockStmt, sink: TokenSink) {
        sink.punct("{"); sink.endLine()
        for (stmt in block.stmts) render(stmt, sink)
        sink.punct("}")
    }

    private fun renderReturnStmt(stmt: Go.ReturnStmt, sink: TokenSink) {
        sink.keyword("return")
        stmt.results.forEachIndexed { i, e ->
            if (i > 0) { sink.punct(","); sink.ws() } else { sink.ws() }
            renderExpr(e, sink)
        }
        sink.endLine()
    }

    private fun renderIfStmt(stmt: Go.IfStmt, sink: TokenSink) {
        sink.keyword("if")
        stmt.init?.let { sink.ws(); render(it, sink); sink.punct(";") }
        sink.ws(); renderExpr(stmt.cond, sink); sink.ws()
        renderBlockStmt(stmt.body, sink)
        val elseBranch = stmt.elseStmt
        if (elseBranch != null) {
            sink.ws(); sink.keyword("else"); sink.ws()
            when (elseBranch) {
                is Go.BlockStmt -> { renderBlockStmt(elseBranch, sink); sink.endLine() }
                is Go.IfStmt -> renderIfStmt(elseBranch, sink) // recursion emits endLine
            }
        } else {
            sink.endLine()
        }
    }

    private fun renderAssignStmt(stmt: Go.AssignStmt, sink: TokenSink) {
        stmt.lhs.forEachIndexed { i, e ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderExpr(e, sink)
        }
        sink.ws()
        when (stmt.op) {
            Go.AssignOp.Assign -> sink.punct("=")
            Go.AssignOp.Define -> sink.punct(":=")
        }
        sink.ws()
        stmt.rhs.forEachIndexed { i, e ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderExpr(e, sink)
        }
        sink.endLine()
    }

    private fun renderCallExpr(call: Go.CallExpr, sink: TokenSink) {
        renderExpr(call.fn, sink)
        sink.bracket("(")
        call.args.forEachIndexed { i, a ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderExpr(a, sink)
        }
        sink.bracket(")")
    }

    private fun renderCompositeLit(lit: Go.CompositeLit, sink: TokenSink) {
        lit.type?.let { renderTypeExpr(it, sink) }
        sink.punct("{")
        lit.elts.forEachIndexed { i, e ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderExpr(e, sink)
        }
        sink.punct("}")
    }

    private fun renderSliceExpr(expr: Go.SliceExpr, sink: TokenSink) {
        renderExpr(expr.x, sink)
        sink.bracket("[")
        expr.low?.let { renderExpr(it, sink) }
        sink.punct(":")
        expr.high?.let { renderExpr(it, sink) }
        sink.bracket("]")
    }

    private fun renderFuncLit(fn: Go.FuncLit, sink: TokenSink) {
        sink.keyword("func")
        sink.bracket("(")
        fn.params.forEachIndexed { i, p ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderField(p, sink)
        }
        sink.bracket(")")
        if (fn.results.isNotEmpty()) {
            sink.ws()
            if (fn.results.size == 1) {
                renderTypeExpr(fn.results[0], sink)
            } else {
                sink.bracket("(")
                fn.results.forEachIndexed { i, r ->
                    if (i > 0) { sink.punct(","); sink.ws() }
                    renderTypeExpr(r, sink)
                }
                sink.bracket(")")
            }
        }
        sink.ws(); renderBlockStmt(fn.body, sink)
    }

    private fun renderIndexTypeExpr(expr: Go.IndexTypeExpr, sink: TokenSink) {
        renderTypeExpr(expr.base, sink)
        sink.bracket("[")
        expr.typeArgs.forEachIndexed { i, t ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderTypeExpr(t, sink)
        }
        sink.bracket("]")
    }

    private fun renderFuncType(ft: Go.FuncType, sink: TokenSink) {
        sink.keyword("func"); sink.bracket("(")
        ft.params.forEachIndexed { i, p ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderField(p, sink)
        }
        sink.bracket(")")
        if (ft.results.isNotEmpty()) {
            sink.ws()
            if (ft.results.size == 1) {
                renderTypeExpr(ft.results[0], sink)
            } else {
                sink.bracket("(")
                ft.results.forEachIndexed { i, r ->
                    if (i > 0) { sink.punct(","); sink.ws() }
                    renderTypeExpr(r, sink)
                }
                sink.bracket(")")
            }
        }
    }

    private fun renderStructType(s: Go.StructType, sink: TokenSink) {
        sink.keyword("struct"); sink.ws(); sink.punct("{"); sink.endLine()
        for (f in s.fields) { renderField(f, sink); sink.endLine() }
        sink.punct("}")
    }

    private fun renderInterfaceType(iface: Go.InterfaceType, sink: TokenSink) {
        sink.keyword("interface"); sink.ws(); sink.punct("{"); sink.endLine()
        for (m in iface.methods) { renderInterfaceMethod(m, sink); sink.endLine() }
        sink.punct("}")
    }

    private fun renderInterfaceMethod(m: Go.InterfaceMethod, sink: TokenSink) {
        sink.name(m.name); sink.bracket("(")
        m.params.forEachIndexed { i, p ->
            if (i > 0) { sink.punct(","); sink.ws() }
            renderField(p, sink)
        }
        sink.bracket(")")
        when {
            m.results.isEmpty() -> {}
            m.results.size == 1 -> { sink.ws(); renderTypeExpr(m.results[0], sink) }
            else -> {
                sink.ws(); sink.bracket("(")
                m.results.forEachIndexed { i, r ->
                    if (i > 0) { sink.punct(","); sink.ws() }
                    renderTypeExpr(r, sink)
                }
                sink.bracket(")")
            }
        }
    }

    private fun renderField(f: Go.Field, sink: TokenSink) {
        f.name?.let { sink.name(it); sink.ws() }
        renderTypeExpr(f.type, sink)
    }

    private fun renderImportSpec(imp: Go.ImportSpec, sink: TokenSink) {
        imp.alias?.let { sink.name(it); sink.ws() }
        sink.quoted("\"${imp.path}\"")
    }

    private fun renderBasicLit(lit: Go.BasicLit, sink: TokenSink) = when (lit.kind) {
        Go.BasicLitKind.String -> sink.quoted(lit.value)
        else -> sink.number(lit.value)
    }

    private fun renderExpr(expr: Go.Expr, sink: TokenSink) = render(expr, sink)
    private fun renderTypeExpr(type: Go.TypeExpr, sink: TokenSink) = render(type, sink)

    // ── TokenSink helpers ─────────────────────────────────────────────────────

    private fun TokenSink.keyword(kw: String) = word(kw)
    private fun TokenSink.name(n: String) = emit(OutputToken(n, OutputTokenType.Name))
    private fun TokenSink.punct(p: String) = punctuation(p)
    private fun TokenSink.ws() = space(" ")
}
