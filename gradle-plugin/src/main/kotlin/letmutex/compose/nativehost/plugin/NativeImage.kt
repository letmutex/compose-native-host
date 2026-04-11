package letmutex.compose.nativehost.plugin

import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

data class NativeImageExperimentConfig(
    // Generated bootstrap class that exposes the fixed Graal C entrypoints.
    val helperMainClass: String,
    // Build-time allowlist for Kotlin main classes the shared library can dispatch to.
    val mainClasses: List<String>,
    val helperSourcesDir: File,
    val helperResourcesDir: File,
    val generatedHelperSourcesDir: File,
    val helperJarFile: File,
    val composeUberJar: Provider<File>,
    val filteredComposeUberJarFile: File,
    val sharedLibraryBaseName: String,
    val generatedJniConfigFile: File,
    // Jar entry prefixes scanned to build JNI metadata for native bridge lookups.
    val generatedJniConfigPackages: List<String>,
    // Extra classes that need JNI access even if they do not live under scanned packages.
    val generatedJniConfigTypes: List<String>,
    val includeResourcePatterns: List<String>,
    val collectedSharedLibraryPatterns: List<String>,
    val preservePackages: List<String>,
    val devNativeLibrariesDir: File,
    val devSharedLibraryFile: File,
    val devSharedLibraryOutputDir: File,
    val devExtraBuildArgs: List<String>,
    val bundleNativeLibrariesDir: File,
    val bundleSharedLibraryFile: File,
    val bundleSharedLibraryOutputDir: File,
    val bundleExtraBuildArgs: List<String>,
)

// The Swift host always calls one stable symbol and passes the requested Kotlin main class at runtime.
private const val sharedLibraryBindMainSymbol = "composeNativeHostRuntimeBindMain"

internal enum class NativeImageSharedLibraryFlavor(
    val taskName: String,
    val description: String,
) {
    Dev(
        taskName = "macosNativeImageBuildSharedLibrary",
        description = "Builds the hosted app shared library for staged native-image runs.",
    ),
    Bundle(
        taskName = "macosNativeImageBuildBundleSharedLibrary",
        description = "Builds the hosted app shared library for patched distributables.",
    ),
}

fun createNativeImageExperimentConfig(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
): NativeImageExperimentConfig {
    val nativeImage = extension.nativeImage
    val sharedLibraryBaseName =
        nativeImage.sharedLibraryBaseName.requireValue("composeNativeHost.nativeImage.sharedLibraryBaseName")
    val mainClasses = resolveNativeImageMainClasses(extension)
    return NativeImageExperimentConfig(
        helperMainClass = generatedNativeImageHelperMainClass(project),
        mainClasses = mainClasses,
        helperSourcesDir = nativeImage.helperSourcesDir.requireValue("composeNativeHost.nativeImage.helperSourcesDir").asFile,
        helperResourcesDir =
            nativeImage.helperResourcesDir.requireValue("composeNativeHost.nativeImage.helperResourcesDir").asFile,
        generatedHelperSourcesDir =
            project.layout.buildDirectory.dir("generated/compose-native-host/native-image/java").get().asFile,
        helperJarFile =
            project.layout.buildDirectory.file(
                "graal-interop/compose-native-host-native-helper.jar",
            ).get().asFile,
        composeUberJar = project.providers.provider { resolveComposeUberJar(project) },
        filteredComposeUberJarFile =
            project.layout.buildDirectory.file(
                "native-image-input/compose-native-host-input.jar",
            ).get().asFile,
        sharedLibraryBaseName = sharedLibraryBaseName,
        devNativeLibrariesDir =
            project.layout.buildDirectory.dir(
                "native-image-libs/dev",
            ).get().asFile,
        devSharedLibraryFile =
            project.layout.buildDirectory.file(
                "native-image-shared/dev/$sharedLibraryBaseName.dylib",
            ).get().asFile,
        devSharedLibraryOutputDir = project.layout.buildDirectory.dir("native-image-shared/dev").get().asFile,
        devExtraBuildArgs = nativeImage.devExtraBuildArgs.get(),
        bundleNativeLibrariesDir =
            project.layout.buildDirectory.dir(
                "native-image-libs/bundle",
            ).get().asFile,
        bundleSharedLibraryFile =
            project.layout.buildDirectory.file(
                "native-image-shared/bundle/$sharedLibraryBaseName.dylib",
            ).get().asFile,
        bundleSharedLibraryOutputDir = project.layout.buildDirectory.dir("native-image-shared/bundle").get().asFile,
        bundleExtraBuildArgs = nativeImage.bundleExtraBuildArgs.get(),
        generatedJniConfigFile =
            project.layout.buildDirectory.file("native-image-config/jni-config.json").get().asFile,
        generatedJniConfigPackages = nativeImage.jniConfigPackages.get(),
        generatedJniConfigTypes = nativeImage.jniConfigTypes.get(),
        includeResourcePatterns = nativeImage.includeResourcePatterns.get(),
        collectedSharedLibraryPatterns = nativeImage.collectedSharedLibraryPatterns.get(),
        preservePackages = nativeImage.preservePackages.get(),
    )
}

fun registerNativeImageGenerateInteropJavaTask(
    project: Project,
    config: NativeImageExperimentConfig,
): TaskProvider<Task> =
    project.tasks.register("macosNativeImageGenerateInteropJava") {
        group = "build"
        description = "Generates GraalVM shared-library entrypoints for the hosted app."
        inputs.property("composeNativeHostNativeImageMainClasses", config.mainClasses)
        outputs.dir(config.generatedHelperSourcesDir)

        doLast {
            config.generatedHelperSourcesDir.deleteRecursively()
            val outputFile = generatedHelperJavaFile(config)
            outputFile.parentFile.mkdirs()
            outputFile.writeText(renderNativeImageHelperJava(config))
        }
    }

fun registerNativeImageFilterComposeUberJarTask(
    project: Project,
    config: NativeImageExperimentConfig,
): TaskProvider<Task> =
    project.tasks.register("macosNativeImageFilterComposeUberJar") {
        group = "build"
        description = "Creates a host-only classpath jar for Compose Native Host native-image builds."
        dependsOn("packageUberJarForCurrentOS")
        inputs.file(config.composeUberJar)
        inputs.property("composeNativeHostNativeImageHostFilter", currentNativeImageHostFilterFingerprint())
        outputs.file(config.filteredComposeUberJarFile)

        doLast {
            val sourceJar = config.composeUberJar.get()
            val targetJar = config.filteredComposeUberJarFile
            targetJar.parentFile.mkdirs()
            filterComposeUberJarForNativeImage(sourceJar, targetJar)
        }
    }

fun registerNativeImageCompileInteropJavaTask(
    project: Project,
    config: NativeImageExperimentConfig,
    generateInteropJava: TaskProvider<Task>,
    filterComposeUberJar: TaskProvider<Task>,
): TaskProvider<Exec> {
    val classesDir = project.layout.buildDirectory.dir("graal-interop/classes").get().asFile
    return project.tasks.register("macosNativeImageCompileInteropJava", Exec::class.java) {
        group = "build"
        description = "Compiles GraalVM shared-library entrypoints for the hosted app."
        dependsOn(filterComposeUberJar, generateInteropJava)
        inputs.files(project.fileTree(config.helperSourcesDir).matching { include("**/*.java") })
        inputs.dir(config.generatedHelperSourcesDir)
        inputs.file(config.filteredComposeUberJarFile)
        outputs.dir(classesDir)

        doFirst {
            val graalHome = resolveGraalVmHome(project)
            val composeUberJar = config.filteredComposeUberJarFile
            val sourceFiles =
                (project.fileTree(config.generatedHelperSourcesDir).matching { include("**/*.java") }.files +
                    project.fileTree(config.helperSourcesDir).matching { include("**/*.java") }.files)
                    .sortedBy { it.absolutePath }
            check(sourceFiles.isNotEmpty()) {
                "No Graal interop Java sources found in ${config.generatedHelperSourcesDir.absolutePath} or ${config.helperSourcesDir.absolutePath}."
            }
            classesDir.deleteRecursively()
            classesDir.mkdirs()
            environment("JAVA_HOME", graalHome)
            commandLine(
                buildList {
                    add("$graalHome/bin/javac")
                    add("--add-modules")
                    add("org.graalvm.nativeimage")
                    add("-cp")
                    add(composeUberJar.absolutePath)
                    add("-d")
                    add(classesDir.absolutePath)
                    addAll(sourceFiles.map { it.absolutePath })
                },
            )
        }
    }
}

fun registerNativeImageInteropJarTask(
    project: Project,
    config: NativeImageExperimentConfig,
    compileInteropJava: TaskProvider<Exec>,
): TaskProvider<Jar> {
    val classesDir = project.layout.buildDirectory.dir("graal-interop/classes").get().asFile
    return project.tasks.register("macosNativeImageInteropJar", Jar::class.java) {
        group = "build"
        description = "Packages GraalVM shared-library entrypoints for the hosted app."
        dependsOn(compileInteropJava)
        archiveFileName.set(config.helperJarFile.name)
        destinationDirectory.set(config.helperJarFile.parentFile)
        from(classesDir)
        from(config.helperResourcesDir)
    }
}

fun registerNativeImageGenerateJniConfigTask(
    project: Project,
    config: NativeImageExperimentConfig,
    filterComposeUberJar: TaskProvider<Task>,
): TaskProvider<Task> =
    project.tasks.register("macosNativeImageGenerateJniConfig") {
        group = "build"
        description = "Generates JNI metadata for the GraalVM shared-library experiment."
        dependsOn(filterComposeUberJar)
        inputs.file(config.filteredComposeUberJarFile)
        inputs.property("composeNativeHostNativeImageJniConfigPackages", config.generatedJniConfigPackages)
        inputs.property("composeNativeHostNativeImageJniConfigTypes", config.generatedJniConfigTypes)
        outputs.file(config.generatedJniConfigFile)

        doLast {
            // The bridge and Skia still rely on JNI lookups from native code, so we generate a
            // conservative config from the packaged jar instead of hand-maintaining a long allowlist.
            val classNames = linkedSetOf<String>()
            ZipFile(config.filteredComposeUberJarFile).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name
                    if (!entryName.endsWith(".class")) {
                        continue
                    }
                    if (config.generatedJniConfigPackages.none(entryName::startsWith)) {
                        continue
                    }
                    val className =
                        entryName
                            .removeSuffix(".class")
                            .replace('/', '.')
                    if (className.endsWith("package-info") || className.endsWith("module-info")) {
                        continue
                    }
                    classNames += className
                }
            }

            val entries =
                buildList {
                    add(
                        """  {"name":"letmutex.compose.nativehost.internal.NativeFrameState","fields":[{"name":"windowInfo"},{"name":"eventCount"},{"name":"records"},{"name":"texts"}],"allDeclaredMethods":true,"allPublicMethods":true,"allDeclaredConstructors":true,"allPublicConstructors":true}""",
                    )
                    add(jniConfigEntry("letmutex.compose.nativehost.internal.MacOsComposeBridgeBindings"))
                    config.generatedJniConfigTypes
                        .filterNot {
                            it == "letmutex.compose.nativehost.internal.NativeFrameState" ||
                                it == "letmutex.compose.nativehost.internal.MacOsComposeBridgeBindings"
                        }.forEach { className ->
                            add(jniConfigEntry(className))
                        }
                    classNames
                        .filterNot { it in config.generatedJniConfigTypes }
                        .forEach { className ->
                            add(jniConfigEntry(className))
                        }
                }

            config.generatedJniConfigFile.parentFile.mkdirs()
            config.generatedJniConfigFile.writeText(
                entries.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]\n"),
            )
        }
    }

internal fun registerNativeImageBuildSharedLibraryTask(
    project: Project,
    config: NativeImageExperimentConfig,
    interopJarTask: TaskProvider<Jar>,
    generateJniConfigTask: TaskProvider<Task>,
    flavor: NativeImageSharedLibraryFlavor,
): TaskProvider<Exec> =
    project.tasks.register(flavor.taskName, Exec::class.java) {
        group = "build"
        description = flavor.description
        dependsOn(interopJarTask, generateJniConfigTask)
        inputs.file(config.filteredComposeUberJarFile)
        inputs.file(config.helperJarFile)
        inputs.file(config.generatedJniConfigFile)
        inputs.property(
            "composeNativeHostNativeImage${flavor.name}ExtraBuildArgs",
            sharedLibraryExtraBuildArgs(config, flavor),
        )
        inputs.property(
            "composeNativeHostNativeImage${flavor.name}IncludeResourcePatterns",
            config.includeResourcePatterns,
        )
        inputs.property(
            "composeNativeHostNativeImage${flavor.name}PreservePackages",
            config.preservePackages,
        )
        outputs.file(sharedLibraryFile(config, flavor))

        doFirst {
            val graalHome = resolveGraalVmHome(project)
            val composeUberJar = config.filteredComposeUberJarFile
            val sharedLibraryOutputDir = sharedLibraryOutputDir(config, flavor)
            sharedLibraryOutputDir.mkdirs()
            environment("JAVA_HOME", graalHome)
            environment("GRAALVM_HOME", graalHome)
            val command =
                buildList {
                    add("$graalHome/bin/native-image")
                    add("--shared")
                    add("--no-fallback")
                    add("-H:+ReportExceptionStackTraces")
                    if (config.includeResourcePatterns.isNotEmpty()) {
                        val includeResourcesPattern =
                            config.includeResourcePatterns.joinToString(separator = "|") { pattern ->
                                "($pattern)"
                            }
                        add("-H:IncludeResources=$includeResourcesPattern")
                    }
                    add("-H:JNIConfigurationFiles=${config.generatedJniConfigFile.absolutePath}")
                    config.preservePackages.forEach { packageName ->
                        add("-H:Preserve=package=$packageName")
                    }
                    addAll(sharedLibraryExtraBuildArgs(config, flavor))
                    add("--add-modules=org.graalvm.nativeimage")
                    add("-cp")
                    add(
                        listOf(config.helperJarFile, composeUberJar)
                            .joinToString(separator = File.pathSeparator) { it.absolutePath },
                    )
                    add("-o")
                    add(File(sharedLibraryOutputDir, config.sharedLibraryBaseName).absolutePath)
                    add(config.helperMainClass)
                }
            commandLine(command)
        }
    }

private fun sharedLibraryFile(
    config: NativeImageExperimentConfig,
    flavor: NativeImageSharedLibraryFlavor,
): File =
    when (flavor) {
        NativeImageSharedLibraryFlavor.Dev -> config.devSharedLibraryFile
        NativeImageSharedLibraryFlavor.Bundle -> config.bundleSharedLibraryFile
    }

private fun sharedLibraryOutputDir(
    config: NativeImageExperimentConfig,
    flavor: NativeImageSharedLibraryFlavor,
): File =
    when (flavor) {
        NativeImageSharedLibraryFlavor.Dev -> config.devSharedLibraryOutputDir
        NativeImageSharedLibraryFlavor.Bundle -> config.bundleSharedLibraryOutputDir
    }

private fun sharedLibraryExtraBuildArgs(
    config: NativeImageExperimentConfig,
    flavor: NativeImageSharedLibraryFlavor,
): List<String> =
    when (flavor) {
        NativeImageSharedLibraryFlavor.Dev -> config.devExtraBuildArgs
        NativeImageSharedLibraryFlavor.Bundle -> config.bundleExtraBuildArgs
    }

private fun resolveComposeUberJar(project: Project): File =
    (project.tasks.findByName("packageUberJarForCurrentOS") as? Jar)
        ?.archiveFile
        ?.get()
        ?.asFile
        ?: throw GradleException(
            "Task packageUberJarForCurrentOS was not found. Configure Compose Desktop application packaging before enabling native-image support.",
        )

private fun filterComposeUberJarForNativeImage(
    sourceJar: File,
    targetJar: File,
) {
    val resourceFilter = nativeImageHostResourceFilter()
    val serviceEntryBytes = nativeHostMainDispatcherFactoryServiceContents()
    ZipFile(sourceJar).use { input ->
        ZipOutputStream(FileOutputStream(targetJar)).use { output ->
            val entries = input.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (
                    entry.isDirectory ||
                    entry.name == mainDispatcherFactoryServiceEntry ||
                    !resourceFilter.keep(entry.name)
                ) {
                    continue
                }
                val outputEntry =
                    ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                        extra = entry.extra
                    }
                output.putNextEntry(outputEntry)
                input.getInputStream(entry).use { it.copyTo(output) }
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry(mainDispatcherFactoryServiceEntry))
            output.write(serviceEntryBytes)
            output.closeEntry()
        }
    }
}

private fun nativeImageHostResourceFilter(): NativeImageHostResourceFilter {
    val osName = System.getProperty("os.name").lowercase(Locale.US)
    val osArch = System.getProperty("os.arch").lowercase(Locale.US)
    return when {
        osName.contains("mac") ->
            NativeImageHostResourceFilter(
                hostLibrarySuffixes = setOf(".dylib", ".jnilib"),
                hostOsTokens = setOf("mac", "macos", "osx", "darwin"),
                hostArchTokens =
                    when (osArch) {
                        "aarch64", "arm64" -> setOf("aarch64", "arm64")
                        "amd64", "x86_64" -> setOf("amd64", "x64", "x86_64")
                        else -> throw GradleException("Unsupported macOS arch for Compose Native Host native-image filtering: ${System.getProperty("os.arch")}")
                    },
            )

        else -> throw GradleException("Unsupported host OS for Compose Native Host native-image filtering: ${System.getProperty("os.name")}")
    }
}

private fun currentNativeImageHostFilterFingerprint(): String {
    val osName = System.getProperty("os.name").lowercase(Locale.US)
    val osArch = System.getProperty("os.arch").lowercase(Locale.US)
    return "$osName::$osArch"
}

private data class NativeImageHostResourceFilter(
    val hostLibrarySuffixes: Set<String>,
    val hostOsTokens: Set<String>,
    val hostArchTokens: Set<String>,
) {
    fun keep(entryName: String): Boolean {
        if (!isNativeArtifact(entryName)) {
            return true
        }
        val normalizedEntryName = entryName.lowercase(Locale.US)
        if (!hostLibrarySuffixes.any { suffix ->
                normalizedEntryName.endsWith(suffix) || normalizedEntryName.endsWith("$suffix.sha256")
            }
        ) {
            return false
        }
        val osTokens = matchingTokens(normalizedEntryName, knownOsTokens)
        if (osTokens.isNotEmpty() && osTokens.none(hostOsTokens::contains)) {
            return false
        }
        val archTokens = matchingTokens(normalizedEntryName, knownArchTokens)
        if (archTokens.isNotEmpty() && archTokens.none(hostArchTokens::contains)) {
            return false
        }
        return true
    }
}

private fun isNativeArtifact(entryName: String): Boolean {
    val normalizedEntryName = entryName.lowercase(Locale.US)
    return nativeFileSuffixes.any(normalizedEntryName::endsWith) ||
        nativeChecksumSuffixes.any(normalizedEntryName::endsWith)
}

private fun matchingTokens(
    entryName: String,
    knownTokens: Set<String>,
): Set<String> =
    knownTokens.filterTo(linkedSetOf()) { token ->
        Regex("(^|[./_\\-])${Regex.escape(token)}($|[./_\\-])").containsMatchIn(entryName)
    }

private val nativeFileSuffixes = setOf(".dylib", ".jnilib", ".so", ".dll")
private val nativeChecksumSuffixes = nativeFileSuffixes.mapTo(linkedSetOf()) { "$it.sha256" }
private val knownOsTokens = setOf("linux", "windows", "mac", "macos", "osx", "darwin", "freebsd")
private val knownArchTokens = setOf("aarch64", "arm64", "armv6", "armv7", "arm", "amd64", "x64", "x86_64", "x86", "ppc64")

private fun generatedNativeImageHelperMainClass(project: Project): String =
    "letmutex.compose.nativehost.generated.${sanitizeJavaIdentifier(project.name)}NativeImageEntryPoint"

private fun resolveNativeImageMainClasses(
    extension: ComposeNativeHostPluginExtension,
): List<String> =
    // Graal needs the full entrypoint registry at build time even though Swift selects the
    // runtime's kotlinMainClass dynamically.
    extension.nativeImage.mainClasses.get().filter(String::isNotBlank).distinct().ifEmpty {
        throw GradleException(
            "Configure composeNativeHost.nativeImage.mainClasses before enabling native-image support.",
        )
    }

private fun jniConfigEntry(
    className: String,
    extraBody: String = "",
): String =
    buildString {
        append("  {\"name\":\"")
        append(className)
        append("\",\"allDeclaredMethods\":true,\"allPublicMethods\":true")
        append(",\"allDeclaredConstructors\":true,\"allPublicConstructors\":true")
        append(",\"allDeclaredFields\":true,\"allPublicFields\":true")
        if (extraBody.isNotEmpty()) {
            append(",")
            append(extraBody)
        }
        append("}")
    }

private fun generatedHelperJavaFile(config: NativeImageExperimentConfig): File {
    val packageName = config.helperMainClass.substringBeforeLast('.', missingDelimiterValue = "")
    val simpleName = config.helperMainClass.substringAfterLast('.')
    val relativePath =
        if (packageName.isEmpty()) {
            "$simpleName.java"
        } else {
            packageName.replace('.', '/') + "/$simpleName.java"
        }
    return File(config.generatedHelperSourcesDir, relativePath)
}

private fun renderNativeImageHelperJava(config: NativeImageExperimentConfig): String {
    val packageName = config.helperMainClass.substringBeforeLast('.', missingDelimiterValue = "")
    val simpleName = config.helperMainClass.substringAfterLast('.')
    // Native-image cannot resolve arbitrary Kotlin main classes reflectively at runtime, so the
    // helper bakes a small switch table for the configured main classes.
    val header = renderNativeImageHelperHeader(packageName, simpleName)
    val body = renderNativeImageHelperBody(config.mainClasses)
    return listOf(header, body).joinToString(separator = "\n")
}

private fun renderNativeImageHelperHeader(
    packageName: String,
    simpleName: String,
): String =
    """
        ${if (packageName.isNotEmpty()) "package $packageName;\n\n        " else ""}
        import letmutex.compose.nativehost.ComposeRuntime;
        import org.graalvm.nativeimage.ObjectHandle;
        import org.graalvm.nativeimage.ObjectHandles;
        import org.graalvm.nativeimage.IsolateThread;
        import org.graalvm.nativeimage.c.function.CEntryPoint;
        import org.graalvm.nativeimage.c.type.CCharPointer;
        import org.graalvm.nativeimage.c.type.CCharPointerPointer;
        import org.graalvm.nativeimage.c.type.CTypeConversion;
        import org.graalvm.word.WordFactory;

        public final class $simpleName {
            private static final ObjectHandles runtimeHandles = ObjectHandles.getGlobal();

            private $simpleName() {
            }

            public static void main(String[] args) {
            }
        
    """.trimIndent()

private fun renderNativeImageHelperBody(mainClasses: List<String>): String =
    """
        private static String[] toJavaStringArray(CCharPointerPointer pointers, int count) {
            if (pointers.isNull() || count <= 0) {
                return new String[0];
            }
            String[] values = new String[count];
            for (int index = 0; index < count; index++) {
                CCharPointer pointer = pointers.read(index);
                values[index] = pointer.isNull() ? "" : CTypeConversion.toJavaString(pointer);
            }
            return values;
        }

        private static String toJavaStringOrNull(CCharPointer pointer) {
            if (pointer.isNull()) {
                return null;
            }
            return CTypeConversion.toJavaString(pointer);
        }

        private static byte[] toJavaByteArrayOrNull(CCharPointer pointer, int count) {
            if (pointer.isNull() || count <= 0) {
                return null;
            }
            byte[] values = new byte[count];
            for (int index = 0; index < count; index++) {
                values[index] = pointer.read(index);
            }
            return values;
        }

        private static ObjectHandle objectHandle(long rawValue) {
            return WordFactory.pointer(rawValue);
        }

        private static ComposeRuntime runtimeFromHandle(long rawValue) {
            if (rawValue == 0L) {
                return null;
            }
            return (ComposeRuntime) runtimeHandles.get(objectHandle(rawValue));
        }

        @CEntryPoint(name = "composeNativeHostRuntimeInitialize")
        static void initialize(IsolateThread thread) {
            ComposeRuntime.initialize();
        }

        @CEntryPoint(name = "composeNativeHostRuntimeCreate")
        static long createRuntime(IsolateThread thread, long runtimeId, int profileRenderingEnabled) {
            ComposeRuntime runtime = new ComposeRuntime(runtimeId, profileRenderingEnabled != 0);
            return runtimeHandles.create(runtime).rawValue();
        }

        @CEntryPoint(name = "$sharedLibraryBindMainSymbol")
        static int bindHostedMain(IsolateThread thread, long runtimeHandle, CCharPointer mainClassPointer) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null || mainClassPointer.isNull()) {
                return 0;
            }
            String mainClassName = CTypeConversion.toJavaString(mainClassPointer);
            ComposeRuntime.enterCurrentRuntime(runtime);
            try {
                switch (mainClassName) {
                    ${mainClasses.joinToString(separator = "\n") { mainClassName ->
                        "                    case \"$mainClassName\": $mainClassName.main(); break;"
                    }}
                    default:
                        return 0;
                }
                return runtime.isContentBound() ? 1 : 0;
            } finally {
                ComposeRuntime.exitCurrentRuntime();
            }
        }

        @CEntryPoint(name = "composeNativeHostRuntimeStart")
        static int startRuntime(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            runtime.startRuntime();
            return 1;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeRequestFrame")
        static void requestFrame(IsolateThread thread, long runtimeHandle, long vsyncNanos) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            runtime.requestFrame(vsyncNanos);
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragEntered")
        static int handleExternalDragEntered(
            IsolateThread thread,
            long runtimeHandle,
            int x,
            int y,
            int action,
            int payloadKind,
            long timestampMillis,
            CCharPointerPointer files,
            int fileCount,
            CCharPointer textPointer,
            CCharPointer imageBytesPointer,
            int imageBytesCount,
            CCharPointer imageFormatPointer
        ) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            return runtime.handleExternalDragEntered(
                x,
                y,
                action,
                payloadKind,
                timestampMillis,
                toJavaStringArray(files, fileCount),
                toJavaStringOrNull(textPointer),
                toJavaByteArrayOrNull(imageBytesPointer, imageBytesCount),
                toJavaStringOrNull(imageFormatPointer)
            ) ? 1 : 0;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragMoved")
        static int handleExternalDragMoved(
            IsolateThread thread,
            long runtimeHandle,
            int x,
            int y,
            int action,
            int payloadKind,
            long timestampMillis,
            CCharPointerPointer files,
            int fileCount,
            CCharPointer textPointer,
            CCharPointer imageBytesPointer,
            int imageBytesCount,
            CCharPointer imageFormatPointer
        ) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            return runtime.handleExternalDragMoved(
                x,
                y,
                action,
                payloadKind,
                timestampMillis,
                toJavaStringArray(files, fileCount),
                toJavaStringOrNull(textPointer),
                toJavaByteArrayOrNull(imageBytesPointer, imageBytesCount),
                toJavaStringOrNull(imageFormatPointer)
            ) ? 1 : 0;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragExited")
        static void handleExternalDragExited(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            runtime.handleExternalDragExited();
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragEnded")
        static void handleExternalDragEnded(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            runtime.handleExternalDragEnded();
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDrop")
        static int handleExternalDrop(
            IsolateThread thread,
            long runtimeHandle,
            int x,
            int y,
            int action,
            int payloadKind,
            long timestampMillis,
            CCharPointerPointer files,
            int fileCount,
            CCharPointer textPointer,
            CCharPointer imageBytesPointer,
            int imageBytesCount,
            CCharPointer imageFormatPointer
        ) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            return runtime.handleExternalDrop(
                x,
                y,
                action,
                payloadKind,
                timestampMillis,
                toJavaStringArray(files, fileCount),
                toJavaStringOrNull(textPointer),
                toJavaByteArrayOrNull(imageBytesPointer, imageBytesCount),
                toJavaStringOrNull(imageFormatPointer)
            ) ? 1 : 0;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeClose")
        static void closeRuntime(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            try {
                runtime.closeRuntime();
            } finally {
                runtimeHandles.destroy(objectHandle(runtimeHandle));
            }
        }
    }
    """.trimIndent()
