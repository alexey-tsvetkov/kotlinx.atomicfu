package kotlinx.atomicfu.plugin.gradle

import kotlinx.atomicfu.transformer.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.*
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilationToRunnableFiles
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.io.*
import java.util.concurrent.Callable

open class AtomicFUGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.add(EXTENSION_NAME, AtomicFUPluginExtension())
        val context = AtomicfuConfigurationContext()

        project.afterEvaluate {
            when {
                project.plugins.hasPlugin("kotlin-multiplatform") -> project.configureMultiplatform(context)
                project.plugins.hasPlugin("kotlin") -> project.configureJvm(context)
                project.plugins.hasPlugin("kotlin2js") -> project.configureJs(context)
            }
        }
    }

    private fun Project.configureMultiplatform(context: AtomicfuConfigurationContext) {
        extensions.findByType(KotlinMultiplatformExtension::class.java)?.let { kotlinExt ->
            for (target in kotlinExt.targets) {
                for (compilation in target.compilations) {
                    MppAtomicfuCompilation(project, compilation).configure(context)
                }
            }
        }
    }

    private fun Project.configureJvm(context: AtomicfuConfigurationContext) {
        for (sourceSet in project.sourceSets) {
            JvmSourceSetAtomicfuCompilation(project, sourceSet).configure(context)
        }
    }

    private fun Project.configureJs(context: AtomicfuConfigurationContext) {
        for (sourceSet in project.sourceSets) {
            JsSourceSetAtomicfuCompilation(project, sourceSet).configure(context)
        }
    }
}

internal class AtomicfuConfigurationContext {
    private val originalClassesDirByCompilation = hashMapOf<AbstractAtomicfuCompilation, FileCollection>()

    fun originalClassesDirs(compilation: AbstractAtomicfuCompilation): FileCollection? =
        originalClassesDirByCompilation[compilation]

    fun setOriginalClassesDirs(compilation: AbstractAtomicfuCompilation, dirs: FileCollection) {
        originalClassesDirByCompilation[compilation] = dirs
    }
}

internal abstract class AbstractAtomicfuCompilation {
    protected abstract fun createTransformTask(originalClassesDirs: FileCollection): Task?

    protected abstract val project: Project
    protected val config: AtomicFUPluginExtension?
        get() = project.extensions.findByName(EXTENSION_NAME) as? AtomicFUPluginExtension

    protected abstract val classesDirs: ConfigurableFileCollection
    protected abstract val transformedClassesDir: File
    protected abstract val compileDependencyFiles: FileCollection
    protected abstract val runtimeDependencyFiles: FileCollection
    protected abstract val compileKotlinTaskName: String
    protected abstract val artifactsTaskName: String

    protected abstract val isTest: Boolean
    protected abstract val testTaskName: String
    protected abstract val mainCompilation: AbstractAtomicfuCompilation

    fun configure(context: AtomicfuConfigurationContext) {
        // make copy of original classes directory
        val originalClassesDirs: FileCollection = project.files(classesDirs.from.toTypedArray()).filter { it.exists() }
        val transformTask = createTransformTask(originalClassesDirs) ?: return
        context.setOriginalClassesDirs(this, originalClassesDirs)
        // make transformedClassesDir the source path for output.classesDirs
        classesDirs.setFrom(transformedClassesDir)
        //now transformTask is responsible for compiling this source set into the classes directory
        classesDirs.builtBy(transformTask)
        (project.tasks.findByName(artifactsTaskName) as? Jar)?.apply {
            setupJarManifest(multiRelease = config?.variant?.toVariant() == Variant.BOTH)
        }

        if (isTest) {
            val originalMainClassesDirs = project.files(
                    // use Callable because there is no guarantee that main is configured before test
                    Callable { context.originalClassesDirs(mainCompilation)!! }
            )

            (project.tasks.findByName(compileKotlinTaskName) as? AbstractCompile)?.classpath =
                    originalMainClassesDirs + compileDependencyFiles - mainCompilation.classesDirs

            (project.tasks.findByName(testTaskName) as? Test)?.classpath =
                    originalMainClassesDirs + runtimeDependencyFiles - mainCompilation.classesDirs
        }
    }
}

internal data class MppAtomicfuCompilation(
    override val project: Project,
    private val compilation: KotlinCompilation
) : AbstractAtomicfuCompilation() {
    private val target = compilation.target

    override val classesDirs get() = compilation.output.classesDirs as ConfigurableFileCollection
    override val transformedClassesDir get() = project.buildDir.resolve("classes/atomicfu/${target.name}/${compilation.name}")
    override val compileDependencyFiles get() = compilation.compileDependencyFiles
    override val runtimeDependencyFiles get() = (compilation as KotlinCompilationToRunnableFiles).runtimeDependencyFiles
    override val compileKotlinTaskName get() = compilation.compileKotlinTaskName
    override val artifactsTaskName get() = target.artifactsTaskName

    override val isTest get() = compilation.name == KotlinCompilation.TEST_COMPILATION_NAME
    override val testTaskName get() = "${target.name}${KotlinCompilation.TEST_COMPILATION_NAME.capitalize()}"
    override val mainCompilation
        get() = MppAtomicfuCompilation(project, target.compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME))

    override fun createTransformTask(originalClassesDirs: FileCollection): Task? = when (target.platformType) {
        KotlinPlatformType.jvm -> {
            project.tasks.create(transformTaskName, AtomicFUTransformTask::class.java)
                    .configureJvmTask(compileDependencyFiles, compilation.compileAllTaskName, transformedClassesDir, originalClassesDirs, config)
        }
        KotlinPlatformType.js -> {
            project.tasks.create(transformTaskName, AtomicFUTransformJsTask::class.java)
                    .configureJsTask(compilation.compileAllTaskName, transformedClassesDir, originalClassesDirs, config)
        }
        // todo KotlinPlatformType.android?
        else -> null
    }

    private val transformTaskName: String
        get() = "transform${compilation.target.name.capitalize()}${compilation.name.capitalize()}Atomicfu"
}

internal abstract class SourceSetAtomicfuCompilation : AbstractAtomicfuCompilation() {
    protected abstract val sourceSet: SourceSet

    override val classesDirs get() = sourceSet.output.classesDirs as ConfigurableFileCollection
    override val transformedClassesDir: File
        get() = File(project.buildDir, "classes${File.separatorChar}${sourceSet.name}-atomicfu")
    override val compileDependencyFiles: FileCollection
        get() = sourceSet.compileClasspath
    override val runtimeDependencyFiles: FileCollection
        get() = sourceSet.runtimeClasspath
    override val artifactsTaskName: String
        get() = sourceSet.jarTaskName

    override val isTest get() = sourceSet.name == SourceSet.TEST_SOURCE_SET_NAME
    override val testTaskName get() = JavaPlugin.TEST_TASK_NAME
}

internal data class JvmSourceSetAtomicfuCompilation(
    override val project: Project,
    override val sourceSet: SourceSet
) : SourceSetAtomicfuCompilation() {
    override fun createTransformTask(originalClassesDirs: FileCollection): Task? =
            project.tasks.create(sourceSet.getTaskName("transform", "atomicfuClasses"), AtomicFUTransformTask::class.java)
                .configureJvmTask(compileDependencyFiles, sourceSet.classesTaskName, transformedClassesDir, originalClassesDirs, config)

    override val compileKotlinTaskName: String get() = sourceSet.getCompileTaskName("Kotlin")
    override val mainCompilation: AbstractAtomicfuCompilation
        get() = JvmSourceSetAtomicfuCompilation(project, project.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME))
}

internal data class JsSourceSetAtomicfuCompilation(
        override val project: Project,
        override val sourceSet: SourceSet
) : SourceSetAtomicfuCompilation() {
    override fun createTransformTask(originalClassesDirs: FileCollection): Task? =
            project.tasks.create(sourceSet.getTaskName("transform", "atomicfuJsFiles"), AtomicFUTransformJsTask::class.java)
                .configureJsTask(sourceSet.classesTaskName, transformedClassesDir, originalClassesDirs, config)

    override val compileKotlinTaskName: String get() = sourceSet.getCompileTaskName("Kotlin2Js")
    override val mainCompilation: AbstractAtomicfuCompilation
        get() = JsSourceSetAtomicfuCompilation(project, project.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME))
}
