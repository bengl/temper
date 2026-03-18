package lang.temper.be.go

import lang.temper.be.Dependencies
import lang.temper.be.cli.CliEnv
import lang.temper.be.cli.EXIT_UNAVAILABLE
import lang.temper.be.cli.Effort
import lang.temper.be.cli.ExecInteractiveRepl
import lang.temper.be.cli.RunBackendSpecificCompilationStepRequest
import lang.temper.be.cli.RunLibraryRequest
import lang.temper.be.cli.RunTestsRequest
import lang.temper.be.cli.ToolchainRequest
import lang.temper.be.cli.ToolchainResult
import lang.temper.common.RFailure
import lang.temper.be.cli.CliFailure

internal fun runGo(
    cliEnv: CliEnv,
    dependencies: Dependencies<GoBackend>,
    request: ToolchainRequest,
): List<ToolchainResult> = when (request) {
    is RunLibraryRequest, is RunTestsRequest, is RunBackendSpecificCompilationStepRequest ->
        listOf(ToolchainResult(result = RFailure(CliFailure(
            message = "Go backend toolchain not yet implemented",
            effort = Effort(exitCode = EXIT_UNAVAILABLE, cliEnv = cliEnv),
        ))))
    is ExecInteractiveRepl ->
        listOf(ToolchainResult(result = RFailure(CliFailure(
            message = "Go backend does not support interactive shell",
            effort = Effort(exitCode = EXIT_UNAVAILABLE, cliEnv = cliEnv),
        ))))
}
