package kotlinx.atomicfu.plugin.gradle

import kotlinx.atomicfu.transformer.AtomicFUTransformer
import kotlinx.atomicfu.transformer.AtomicFUTransformerJS
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar
import java.io.File

@CacheableTask
open class AtomicFUTransformTask : ConventionTask() {
    @InputFiles
    lateinit var inputFiles: FileCollection

    @OutputDirectory
    lateinit var outputDir: File

    @InputFiles
    lateinit var classPath: FileCollection

    @Input
    var variant = "FU"

    @Input
    var verbose = false

    @TaskAction
    fun transform() {
        val cp = classPath.files.map { it.absolutePath }
        inputFiles.files.forEach { inputDir ->
            AtomicFUTransformer(cp, inputDir, outputDir).let { t ->
                t.variant = variant.toVariant()
                t.verbose = verbose
                t.transform()
            }
        }
    }
}

@CacheableTask
open class AtomicFUTransformJsTask : ConventionTask() {
    @InputFiles
    lateinit var inputFiles: FileCollection
    @OutputDirectory
    lateinit var outputDir: File
    @Input
    var verbose = false

    @TaskAction
    fun transform() {
        inputFiles.files.forEach { inputDir ->
            AtomicFUTransformerJS(inputDir, outputDir).let { t ->
                t.verbose = verbose
                t.transform()
            }
        }
    }
}

internal fun AtomicFUTransformTask.configureJvmTask(
        classpath: FileCollection,
        classesTaskName: String,
        transformedClassesDir: File,
        originalClassesDir: FileCollection,
        config: AtomicFUPluginExtension?
): ConventionTask =
        apply {
            dependsOn(classesTaskName)
            classPath = classpath
            inputFiles = originalClassesDir
            outputDir = transformedClassesDir
            config?.let {
                variant = it.variant
                verbose = it.verbose
            }
        }

internal fun AtomicFUTransformJsTask.configureJsTask(
        classesTaskName: String,
        transformedClassesDir: File,
        originalClassesDir: FileCollection,
        config: AtomicFUPluginExtension?
): ConventionTask =
        apply {
            dependsOn(classesTaskName)
            inputFiles = originalClassesDir
            outputDir = transformedClassesDir
            config?.let {
                verbose = it.verbose
            }
        }

internal fun Jar.setupJarManifest(multiRelease: Boolean, classifier: String = "") {
    this.classifier = classifier // todo: why we overwrite jar's classifier?
    if (multiRelease) {
        manifest.attributes.apply {
            put("Multi-Release", "true")
        }
    }
}