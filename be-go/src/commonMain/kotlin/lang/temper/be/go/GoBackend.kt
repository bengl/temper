package lang.temper.be.go

import lang.temper.be.Backend
import lang.temper.be.BackendSetup
import lang.temper.be.storeDescriptorsForDeclarations
import lang.temper.be.tmpl.TmpL
import lang.temper.be.tmpl.TmpLTranslator
import lang.temper.common.MimeType
import lang.temper.fs.ResourceDescriptor
import lang.temper.fs.declareResources
import lang.temper.log.dirPath
import lang.temper.log.filePath
import lang.temper.log.resolveDir
import lang.temper.name.BackendId
import lang.temper.name.BackendMeta
import lang.temper.name.FileType
import lang.temper.name.LanguageLabel

class GoBackend(setup: BackendSetup<GoBackend>) : Backend<GoBackend>(Factory.backendId, setup) {

    override fun tentativeTmpL(): TmpL.ModuleSet =
        TmpLTranslator.translateModules(
            logSink,
            readyModules,
            GoSupportNetwork,
            libraryConfigurations,
            dependencyResolver,
            ::tentativeOutputPathFor,
        ).also { storeDescriptorsForDeclarations(it, Factory) }

    override fun translate(finished: TmpL.ModuleSet): List<OutputFileSpecification> {
        val modules = finished.modules
        return buildList {
            // Translate each module.
            for (module in modules) {
                val translator = GoTranslator(module, logSink)
                add(translator.translateModule())
            }
            // Generate go.mod file.
            val libraryConfiguration = libraryConfigurations.currentLibraryConfiguration
            val modulePath = "temper/${libraryConfiguration.libraryName.text}"
            val goModContent = buildString {
                appendLine("module $modulePath")
                appendLine()
                appendLine("go 1.18")
            }
            add(
                MetadataFileSpecification(
                    path = lang.temper.log.filePath("go.mod"),
                    mimeType = null,
                    content = goModContent,
                ),
            )
        }
    }

    override val supportNetwork = GoSupportNetwork

    private fun tentativeOutputPathFor(module: lang.temper.frontend.Module) =
        allocateTextFile(module, FILE_EXTENSION, defaultName = "module")

    companion object {
        internal const val BACKEND_ID = "go"
        const val FILE_EXTENSION = ".go"
        val mimeType = MimeType("text", "go")
        private val resourceBase = dirPath("lang", "temper", "be", "go")
        private val coreResourceBase = resourceBase.resolveDir("temper-core")
    }

    @PluginBackendId(BACKEND_ID)
    @BackendSupportLevel(isSupported = false, isDefaultSupported = false, isTested = true)
    object Factory : Backend.Factory<GoBackend> {
        override val backendId = BackendId(BACKEND_ID)
        override val specifics = GoSpecifics

        override val backendMeta: BackendMeta
            get() = BackendMeta(
                backendId = backendId,
                languageLabel = LanguageLabel(backendId.uniqueId),
                fileExtensionMap = mapOf(FileType.Module to FILE_EXTENSION),
                mimeTypeMap = mapOf(FileType.Module to mimeType),
            )

        override val coreLibraryResources: List<ResourceDescriptor> =
            declareResources(
                base = coreResourceBase,
                filePath("go.mod"),
                filePath("result.go"),
                filePath("string.go"),
                filePath("list.go"),
                filePath("math.go"),
            )

        override fun make(setup: BackendSetup<GoBackend>) = GoBackend(setup)
    }
}
