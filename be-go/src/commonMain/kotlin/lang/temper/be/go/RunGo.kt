package lang.temper.be.go

import lang.temper.be.Dependencies
import lang.temper.be.cli.Aux
import lang.temper.be.cli.CliEnv
import lang.temper.be.cli.CliFailure
import lang.temper.be.cli.Command
import lang.temper.be.cli.EXIT_UNAVAILABLE
import lang.temper.be.cli.Effort
import lang.temper.be.cli.ExecInteractiveRepl
import lang.temper.be.cli.RunBackendSpecificCompilationStepRequest
import lang.temper.be.cli.RunLibraryRequest
import lang.temper.be.cli.RunTestsRequest
import lang.temper.be.cli.ToolchainRequest
import lang.temper.be.cli.ToolchainResult
import lang.temper.be.cli.maybeLogBeforeRunning
import lang.temper.common.RFailure
import lang.temper.library.relativeOutputDirectoryForLibrary
import lang.temper.log.resolveFile
import lang.temper.name.DashedIdentifier

internal fun runGo(
    cliEnv: CliEnv,
    dependencies: Dependencies<GoBackend>,
    request: ToolchainRequest,
): List<ToolchainResult> = when (request) {
    is RunLibraryRequest -> cliEnv.runLibrary(request)
    is RunTestsRequest ->
        listOf(
            ToolchainResult(
                result = RFailure(
                    CliFailure(
                        message = "Go backend does not yet support running tests",
                        effort = Effort(exitCode = EXIT_UNAVAILABLE, cliEnv = cliEnv),
                    ),
                ),
            ),
        )
    is RunBackendSpecificCompilationStepRequest -> error(request)
    is ExecInteractiveRepl ->
        listOf(
            ToolchainResult(
                result = RFailure(
                    CliFailure(
                        message = "Go backend does not support interactive shell",
                        effort = Effort(exitCode = EXIT_UNAVAILABLE, cliEnv = cliEnv),
                    ),
                ),
            ),
        )
}.also { results ->
    if (results.any { it.result is RFailure }) {
        cliEnv.maybeFreeze()
    }
}

private fun CliEnv.runGoCommand(subcommand: String, libraryName: DashedIdentifier): ToolchainResult {
    val runDir = relativeOutputDirectoryForLibrary(GoBackend.Factory.backendId, libraryName)
    val goCmd = this[GoCommand]
    val command = Command(
        args = listOf(subcommand, "."),
        aux = mapOf(Aux.Stderr to runDir.resolveFile("stderr.txt")),
        cwd = runDir,
    )
    command.maybeLogBeforeRunning(goCmd, shellPreferences)
    return ToolchainResult(libraryName = libraryName, result = goCmd.run(command))
}

private fun CliEnv.runLibrary(request: RunLibraryRequest): List<ToolchainResult> {
    return listOf(runGoCommand("run", request.libraryName))
}
