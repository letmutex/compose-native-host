package letmutex.compose.nativehost.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class ComposeNativeHostPluginExtension
    @Inject
    constructor(
        objects: ObjectFactory,
        project: Project,
    ) {
        /** Application name used for bundle metadata and default executable naming. */
        val appName: Property<String> = objects.property(String::class.java)
        /** macOS bundle identifier written into the generated `Info.plist`. */
        val bundleIdentifier: Property<String> = objects.property(String::class.java)
        /** Native launcher executable name. Defaults to a sanitized `appName`. */
        val executableName: Property<String> = objects.property(String::class.java)
        /** Extra JVM arguments passed to the staged JVM runtime launch path. */
        val jvmArgs: ListProperty<String> = objects.listProperty(String::class.java)
        /** Kotlin Multiplatform JVM target name that provides the hosted app classes. */
        val jvmTargetName: Property<String> = objects.property(String::class.java)
        /** macOS-specific bundle, signing, and launcher source settings. */
        val macos: ComposeNativeHostMacOsExtension =
            objects.newInstance(ComposeNativeHostMacOsExtension::class.java, objects, project, appName, executableName)
        /** GraalVM native-image shared-library settings for the hosted runtime. */
        val nativeImage: ComposeNativeHostNativeImageExtension =
            objects.newInstance(
                ComposeNativeHostNativeImageExtension::class.java,
                objects,
                project,
            )

        init {
            executableName.convention(appName.map(::sanitizeExecutableName))
            jvmTargetName.convention("desktop")
        }

        fun jvmArgs(vararg args: String) {
            jvmArgs.addAll(args.asList())
        }

        fun macos(configure: ComposeNativeHostMacOsExtension.() -> Unit) {
            macos.configure()
        }

        fun nativeImage(configure: ComposeNativeHostNativeImageExtension.() -> Unit) {
            nativeImage.configure()
        }
    }

abstract class ComposeNativeHostNativeImageExtension
    @Inject
    constructor(
        objects: ObjectFactory,
        project: Project,
    ) {
        /** Kotlin top-level main classes to register in the generated native-image entrypoint switch. */
        val mainClasses: ListProperty<String> = objects.listProperty(String::class.java)
        /** Optional Java source directory for additional Graal interop entrypoints or helpers. */
        val helperSourcesDir: DirectoryProperty = objects.directoryProperty()
        /** Optional resource directory packaged into the generated Graal interop helper jar. */
        val helperResourcesDir: DirectoryProperty = objects.directoryProperty()
        /** Basename for the produced shared library without platform extension. */
        val sharedLibraryBaseName: Property<String> = objects.property(String::class.java)
        /** Additional jar entry prefixes scanned when generating JNI metadata from the packaged app jar. */
        val jniConfigPackages: ListProperty<String> = objects.listProperty(String::class.java)
        /** Additional fully qualified class names that should always be included in generated JNI metadata. */
        val jniConfigTypes: ListProperty<String> = objects.listProperty(String::class.java)
        /** Additional `native-image` resource include patterns appended to the default Compose patterns. */
        val includeResourcePatterns: ListProperty<String> = objects.listProperty(String::class.java)
        /** Optional jar entry regex patterns for shared libraries copied beside the runtime dylib. */
        val collectedSharedLibraryPatterns: ListProperty<String> = objects.listProperty(String::class.java)
        /** Additional package names preserved in the shared-library build for JNI and runtime lookup stability. */
        val preservePackages: ListProperty<String> = objects.listProperty(String::class.java)
        /** Extra raw `native-image` CLI flags appended to the staged app shared-library build. */
        val devExtraBuildArgs: ListProperty<String> = objects.listProperty(String::class.java)
        /** Extra raw `native-image` CLI flags appended to distributable shared-library builds. */
        val bundleExtraBuildArgs: ListProperty<String> = objects.listProperty(String::class.java)

        init {
            helperSourcesDir.convention(project.layout.projectDirectory.dir("src/graalInterop/java"))
            helperResourcesDir.convention(project.layout.projectDirectory.dir("src/graalInterop/resources"))
            sharedLibraryBaseName.convention("libcompose-native-host-runtime")
            jniConfigPackages.value(listOf("org/jetbrains/skia/", "org/jetbrains/skiko/"))
            jniConfigTypes.value(
                listOf(
                    "letmutex.compose.nativehost.internal.NativeFrameState",
                    "letmutex.compose.nativehost.internal.MacOsComposeBridgeBindings",
                    "java.io.OutputStream",
                    "java.lang.Boolean",
                    "java.lang.Float",
                    "java.lang.RuntimeException",
                    "java.lang.String",
                    "java.lang.System",
                    "java.lang.Throwable",
                    "java.util.Iterator",
                    "kotlin.jvm.functions.Function0",
                ),
            )
            includeResourcePatterns.value(
                listOf(
                    "libskiko-.*\\.sha256",
                    "composeResources/.*",
                ),
            )
            collectedSharedLibraryPatterns.value(
                listOf(
                    ".*libskiko-macos-.*\\.dylib",
                ),
            )
            preservePackages.value(
                listOf(
                    "org.jetbrains.skia.impl",
                    "org.jetbrains.skia",
                    "org.jetbrains.skiko",
                ),
            )
        }

        internal fun isConfigured(): Boolean =
            mainClasses.get().any(String::isNotBlank)

        fun mainClasses(vararg mainClasses: String) {
            this.mainClasses.addAll(mainClasses.asList())
        }

        fun devExtraBuildArgs(vararg args: String) {
            devExtraBuildArgs.addAll(args.asList())
        }

        fun bundleExtraBuildArgs(vararg args: String) {
            bundleExtraBuildArgs.addAll(args.asList())
        }

        fun collectedSharedLibraryPatterns(vararg patterns: String) {
            collectedSharedLibraryPatterns.addAll(patterns.asList())
        }
    }

abstract class ComposeNativeHostMacOsExtension
    @Inject
    constructor(
        objects: ObjectFactory,
        private val project: Project,
        appName: Property<String>,
        executableName: Property<String>,
    ) {
        /** Final `.app` bundle name, including the `.app` suffix. */
        val bundleName: Property<String> = objects.property(String::class.java)
        /** Display name shown by macOS for the app bundle. */
        val bundleDisplayName: Property<String> = objects.property(String::class.java)
        /** CFBundleVersion value written into the generated `Info.plist`. */
        val bundleVersion: Property<String> = objects.property(String::class.java)
        /** CFBundleShortVersionString value written into the generated `Info.plist`. */
        val shortVersion: Property<String> = objects.property(String::class.java)
        /** Minimum supported macOS version for the generated bundle metadata. */
        val minimumSystemVersion: Property<String> = objects.property(String::class.java)
        /** Code-signing identity passed to `codesign` for staged and distributable bundles. */
        val codeSignIdentity: Property<String> = objects.property(String::class.java)
        /** Swift launcher source directory used when compiling the native host app launcher. */
        val launcherSourcesDir: DirectoryProperty = objects.directoryProperty()
        private val bundleContents = mutableListOf<ComposeNativeHostBundleContent>()

        init {
            bundleName.convention(executableName.map { "$it.app" })
            bundleDisplayName.convention(appName)
            bundleVersion.convention("1")
            shortVersion.convention("1.0.0")
            minimumSystemVersion.convention("13.0")
            codeSignIdentity.convention("-")
            launcherSourcesDir.convention(project.layout.projectDirectory.dir("src/macosMain/swift"))
        }

        fun bundleContent(
            from: Any,
            into: String,
            executable: Boolean = false,
            builtBy: Any? = null,
            runtimes: Set<ComposeNativeHostTargetRuntime> = ComposeNativeHostTargetRuntime.entries.toSet(),
        ) {
            val files = project.files(from)
            if (builtBy != null) {
                files.builtBy(builtBy)
            }
            bundleContents +=
                ComposeNativeHostBundleContent(
                    files = files,
                    into = into,
                    executable = executable,
                    runtimes = runtimes.toSet(),
                )
        }

        internal fun bundleContents(): List<ComposeNativeHostBundleContent> = bundleContents.toList()
    }

data class ComposeNativeHostBundleContent(
    val files: ConfigurableFileCollection,
    val into: String,
    val executable: Boolean,
    val runtimes: Set<ComposeNativeHostTargetRuntime>,
)

fun createExtension(project: Project): ComposeNativeHostPluginExtension =
    project.extensions.create(
        "composeNativeHost",
        ComposeNativeHostPluginExtension::class.java,
        project.objects,
        project,
    )

fun sanitizeExecutableName(appName: String): String =
    appName.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .ifBlank { throw GradleException("composeNativeHost.appName must contain at least one valid executable character.") }

fun sanitizeJavaIdentifier(value: String): String {
    val pieces = value.split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .map { piece -> piece.replaceFirstChar { char -> char.uppercase() } }
    val combined = pieces.joinToString(separator = "")
    val normalized = combined.ifBlank { "ComposeNativeHost" }
    return if (normalized.first().isDigit()) {
        "Compose$normalized"
    } else {
        normalized
    }
}

fun Property<String>.requireValue(name: String): String =
    orNull ?: throw GradleException("$name must be configured.")

fun DirectoryProperty.requireValue(name: String) =
    orNull ?: throw GradleException("$name must be configured.")
