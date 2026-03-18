package lang.temper.be.go

import lang.temper.library.LibraryConfiguration
import lang.temper.log.FilePath
import lang.temper.name.Symbol
import lang.temper.value.TString

/**
 * Holds module path information for a Go library build.
 * Named GoModulePaths to avoid conflict with the GoNames utility object below.
 */
class GoModulePaths(
    val modulePath: String,
    val modulePathsByRoot: Map<FilePath, String>,
)

internal val goModulePathKey = Symbol("goModulePath")

internal fun makeGoModulePaths(backend: GoBackend): GoModulePaths {
    val config = backend.libraryConfigurations.currentLibraryConfiguration
    val modulePath = resolveModulePath(config)
    val byRoot = backend.libraryConfigurations.byLibraryRoot.values
        .associate { it.libraryRoot to resolveModulePath(it) }
    return GoModulePaths(modulePath = modulePath, modulePathsByRoot = byRoot)
}

private fun resolveModulePath(config: LibraryConfiguration): String =
    TString.unpackOrNull(config.configExports[goModulePathKey])
        ?: error("Go backend requires 'goModulePath' config to be set for library '${config.libraryName}'")

/** Pure static name-mangling utilities. */
object GoNames {
    fun toExported(name: String): String = name.replaceFirstChar { it.uppercaseChar() }
    fun toUnexported(name: String): String = name.replaceFirstChar { it.lowercaseChar() }
    fun packageNameFromSegment(segment: String): String = segment.lowercase().replace('-', '_')
}
