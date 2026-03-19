package lang.temper.be.go

/** Pure static name-mangling utilities. */
object GoNames {
    fun toExported(name: String): String = name.replaceFirstChar { it.uppercaseChar() }
    fun toUnexported(name: String): String = name.replaceFirstChar { it.lowercaseChar() }
    fun packageNameFromSegment(segment: String): String = segment.lowercase().replace('-', '_')
}
