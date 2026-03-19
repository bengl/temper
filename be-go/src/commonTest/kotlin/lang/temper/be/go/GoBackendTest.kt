package lang.temper.be.go

import lang.temper.format.toStringViaTokenSink
import lang.temper.log.unknownPos
import kotlin.test.Test
import kotlin.test.assertEquals

class GoBackendTest {

    private val pos = unknownPos

    private fun render(node: Go.Node): String =
        toStringViaTokenSink(singleLine = true) { sink -> GoRenderer.render(node, sink) }

    @Test
    fun identRenders() {
        assertEquals("foo", render(Go.Ident(pos, "foo")))
    }

    @Test
    fun basicLitStringRenders() {
        assertEquals("\"hello\"", render(Go.BasicLit(pos, Go.BasicLitKind.String, "\"hello\"")))
    }

    @Test
    fun selectorExprRenders() {
        assertEquals(
            "fmt.Println",
            render(
                Go.SelectorExpr(pos, Go.Ident(pos, "fmt"), "Println"),
            ),
        )
    }

    @Test
    fun callExprRenders() {
        assertEquals(
            "fmt.Println(\"hello\")",
            render(
                Go.CallExpr(
                    pos,
                    Go.SelectorExpr(pos, Go.Ident(pos, "fmt"), "Println"),
                    listOf(Go.BasicLit(pos, Go.BasicLitKind.String, "\"hello\"")),
                ),
            ),
        )
    }

    @Test
    fun returnStmtSingleValue() {
        assertEquals(
            "return x",
            render(Go.ReturnStmt(pos, listOf(Go.Ident(pos, "x")))),
        )
    }

    @Test
    fun returnStmtMultipleValues() {
        assertEquals(
            "return x, nil",
            render(
                Go.ReturnStmt(pos, listOf(Go.Ident(pos, "x"), Go.Ident(pos, "nil"))),
            ),
        )
    }

    @Test
    fun funcDeclRenders() {
        val fn = Go.FuncDecl(
            pos,
            name = "Hello",
            receiver = null,
            params = emptyList(),
            results = emptyList(),
            body = Go.BlockStmt(
                pos,
                listOf(
                    Go.ExprStmt(
                        pos,
                        Go.CallExpr(
                            pos,
                            Go.SelectorExpr(pos, Go.Ident(pos, "fmt"), "Println"),
                            listOf(Go.BasicLit(pos, Go.BasicLitKind.String, "\"Hello, World!\"")),
                        ),
                    ),
                ),
            ),
        )
        val result = render(fn)
        assertEquals("func Hello() {fmt.Println(\"Hello, World!\")}", result)
    }

    @Test
    fun binaryExprRenders() {
        assertEquals(
            "x + y",
            render(Go.BinaryExpr(pos, Go.Ident(pos, "x"), Go.BinOp.Plus, Go.Ident(pos, "y"))),
        )
    }

    @Test
    fun unaryExprRenders() {
        assertEquals(
            "-x",
            render(Go.UnaryExpr(pos, Go.UnaryOp.Neg, Go.Ident(pos, "x"))),
        )
        assertEquals(
            "!done",
            render(Go.UnaryExpr(pos, Go.UnaryOp.Not, Go.Ident(pos, "done"))),
        )
    }

    @Test
    fun assignStmtRenders() {
        assertEquals(
            "x = 5",
            render(
                Go.AssignStmt(
                    pos,
                    lhs = listOf(Go.Ident(pos, "x")),
                    rhs = listOf(Go.BasicLit(pos, Go.BasicLitKind.Int, "5")),
                    op = Go.AssignOp.Assign,
                ),
            ),
        )
    }

    @Test
    fun defineStmtRenders() {
        assertEquals(
            "x := 5",
            render(
                Go.AssignStmt(
                    pos,
                    lhs = listOf(Go.Ident(pos, "x")),
                    rhs = listOf(Go.BasicLit(pos, Go.BasicLitKind.Int, "5")),
                    op = Go.AssignOp.Define,
                ),
            ),
        )
    }

    @Test
    fun forStmtWhileStyleRenders() {
        assertEquals(
            "for x > 0{x = 1}",
            render(
                Go.ForStmt(
                    pos,
                    init = null,
                    cond = Go.BinaryExpr(
                        pos,
                        Go.Ident(pos, "x"),
                        Go.BinOp.Gt,
                        Go.BasicLit(pos, Go.BasicLitKind.Int, "0"),
                    ),
                    post = null,
                    body = Go.BlockStmt(
                        pos,
                        listOf(
                            Go.AssignStmt(
                                pos,
                                lhs = listOf(Go.Ident(pos, "x")),
                                rhs = listOf(Go.BasicLit(pos, Go.BasicLitKind.Int, "1")),
                                op = Go.AssignOp.Assign,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun ifStmtRenders() {
        assertEquals(
            "if x > 0{return x}",
            render(
                Go.IfStmt(
                    pos,
                    init = null,
                    cond = Go.BinaryExpr(
                        pos,
                        Go.Ident(pos, "x"),
                        Go.BinOp.Gt,
                        Go.BasicLit(pos, Go.BasicLitKind.Int, "0"),
                    ),
                    body = Go.BlockStmt(
                        pos,
                        listOf(Go.ReturnStmt(pos, listOf(Go.Ident(pos, "x")))),
                    ),
                    elseStmt = null,
                ),
            ),
        )
    }

    @Test
    fun ifElseStmtRenders() {
        assertEquals(
            "if x > 0{return x} else {return y}",
            render(
                Go.IfStmt(
                    pos,
                    init = null,
                    cond = Go.BinaryExpr(
                        pos,
                        Go.Ident(pos, "x"),
                        Go.BinOp.Gt,
                        Go.BasicLit(pos, Go.BasicLitKind.Int, "0"),
                    ),
                    body = Go.BlockStmt(
                        pos,
                        listOf(Go.ReturnStmt(pos, listOf(Go.Ident(pos, "x")))),
                    ),
                    elseStmt = Go.BlockStmt(
                        pos,
                        listOf(Go.ReturnStmt(pos, listOf(Go.Ident(pos, "y")))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun goEscapeStringHandlesSpecialChars() {
        assertEquals("hello", GoTranslator.goEscapeString("hello"))
        assertEquals("line1\\nline2", GoTranslator.goEscapeString("line1\nline2"))
        assertEquals("tab\\there", GoTranslator.goEscapeString("tab\there"))
        assertEquals("say \\\"hi\\\"", GoTranslator.goEscapeString("say \"hi\""))
        assertEquals("back\\\\slash", GoTranslator.goEscapeString("back\\slash"))
        assertEquals("cr\\r", GoTranslator.goEscapeString("cr\r"))
    }

    @Test
    fun goNamesExportedIsPascalCase() {
        assertEquals("MyFunc", GoNames.toExported("myFunc"))
        assertEquals("MyFunc", GoNames.toExported("MyFunc"))
    }

    @Test
    fun goNamesUnexportedIsCamelCase() {
        assertEquals("myFunc", GoNames.toUnexported("MyFunc"))
        assertEquals("myFunc", GoNames.toUnexported("myFunc"))
    }

    @Test
    fun goNamesPackageFromModule() {
        assertEquals("utils", GoNames.packageNameFromSegment("utils"))
        assertEquals("my_utils", GoNames.packageNameFromSegment("my-utils"))
    }
}
