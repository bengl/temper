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
    fun returnStmtRenders() {
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
        assert(result.contains("func Hello()")) { "Expected func Hello(), got: $result" }
        assert(result.contains("fmt.Println")) { "Expected fmt.Println, got: $result" }
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
