package lang.temper.be.go

import lang.temper.be.Dependencies
import lang.temper.be.cli.Aux
import lang.temper.be.cli.CliEnv
import lang.temper.be.cli.CliFailure
import lang.temper.be.cli.EXIT_UNAVAILABLE
import lang.temper.be.cli.Effort
import lang.temper.be.cli.EffortSuccess
import lang.temper.be.cli.RunnerSpecifics
import lang.temper.be.cli.ToolchainRequest
import lang.temper.be.cli.ToolchainResult
import lang.temper.be.cli.VersionedTool
import lang.temper.be.cli.checkMin
import lang.temper.common.RFailure
import lang.temper.common.RResult
import lang.temper.fs.OutDir
import lang.temper.log.FilePath
import lang.temper.name.SemVer

object GoSpecifics : RunnerSpecifics {
    override val backendId get() = GoBackend.Factory.backendId
    override val tools = listOf(GoCommand)

    override fun runSingleSource(
        cliEnv: CliEnv,
        code: String,
        env: Map<String, String>,
        aux: Map<Aux, FilePath>,
    ): RResult<EffortSuccess, CliFailure> = RFailure(
        CliFailure(
            message = "Go backend does not yet support single-source execution",
            effort = Effort(exitCode = EXIT_UNAVAILABLE, cliEnv = cliEnv),
        ),
    )

    override fun runBestEffort(
        cliEnv: CliEnv,
        request: ToolchainRequest,
        code: OutDir,
        dependencies: Dependencies<*>,
    ): List<ToolchainResult> = runGo(
        cliEnv = cliEnv,
        dependencies = @Suppress("UNCHECKED_CAST") (dependencies as Dependencies<GoBackend>),
        request = request,
    )
}

object GoCommand : VersionedTool {
    override val cliNames = listOf("go")
    override val versionCheckArgs = listOf("version")

    override fun checkVersion(run: EffortSuccess): RResult<Unit, CliFailure> {
        // "go version go1.24.0 linux/amd64"
        val versionString = run.stdout.trim().split(" ").getOrNull(2)?.removePrefix("go") ?: ""
        return SemVer(versionString).checkMin(run, minVersion)
    }

    @Suppress("MagicNumber")
    val minVersion = SemVer(1, 18, 0)
}
