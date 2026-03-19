package lang.temper.be.go

import lang.temper.be.FunctionalTestRunner
import lang.temper.be.assertRunOutput
import lang.temper.be.assertTestingTest
import lang.temper.be.cli.CliEnv
import lang.temper.be.cli.ShellPreferences
import lang.temper.be.cli.ToolchainRequest
import lang.temper.be.cli.print
import lang.temper.common.console
import lang.temper.frontend.Module
import lang.temper.fs.OutDir
import lang.temper.fs.OutputRoot
import lang.temper.log.FilePath
import lang.temper.log.FilePathSegment
import lang.temper.name.ModuleName
import lang.temper.tests.FunctionalTestBase
import kotlin.test.Test

class GoFunctionalTest : FunctionalTestRunner<GoBackend>(GoBackend.Factory) {
    @Test
    override fun algosHelloWorld() {
        super.algosHelloWorld()
    }

    override fun runGeneratedCode(
        backend: GoBackend,
        modules: List<Module>,
        outputRoot: OutputRoot,
        outputDir: OutDir,
        outputPaths: Map<ModuleName, FilePath>,
        test: FunctionalTestBase,
        request: ToolchainRequest,
    ) {
        CliEnv.using(factory.specifics, ShellPreferences.functionalTests(console), cancelGroup) {
            // Copy output files into the CLI environment.
            copyOutputDir(outputRoot, FilePath.emptyPath)
            // Copy temper-core resources.
            copyResources(
                factory.coreLibraryResources,
                FilePath(
                    listOf(factory.backendId.uniqueId, "temper-core").map { FilePathSegment(it) },
                    isDir = true,
                ),
            )
            // Run the generated Go code.
            val result = runGo(
                cliEnv = this,
                dependencies = backend.getDependencies(),
                request = request,
            ).first().result
            // Check results.
            var pass = false
            try {
                when {
                    test.runAsTest -> assertTestingTest(test, result)
                    else -> test.assertRunOutput(result)
                }
                pass = true
            } finally {
                if (!pass) {
                    result.print(console)
                }
            }
        }
    }
}
