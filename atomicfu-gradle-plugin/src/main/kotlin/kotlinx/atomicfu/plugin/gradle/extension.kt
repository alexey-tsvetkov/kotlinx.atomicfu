package kotlinx.atomicfu.plugin.gradle

import kotlinx.atomicfu.transformer.Variant
import java.util.*

const val EXTENSION_NAME = "atomicfu"

class AtomicFUPluginExtension {
    var variant: String = "FU"
    var verbose: Boolean = false
}

internal fun String.toVariant(): Variant = enumValueOf(toUpperCase(Locale.US))