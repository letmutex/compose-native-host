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
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

fun createNativeImageExperimentConfig(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
): NativeImageExperimentConfig {
    val nativeImage = extension.nativeImage
    val sharedLibraryBaseName =
        nativeImage.sharedLibraryBaseName.requireValue("composeNativeHost.nativeImage.sharedLibraryBaseName")
    val mainClasses = resolveNativeImageMainClasses(extension)
    val sharedLibSuffix = if (hostOsPrefix == "windows") ".dll" else ".dylib"
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
                "native-image-shared/dev/$sharedLibraryBaseName$sharedLibSuffix",
            ).get().asFile,
        devSharedLibraryOutputDir = project.layout.buildDirectory.dir("native-image-shared/dev").get().asFile,
        devExtraBuildArgs = nativeImage.devExtraBuildArgs.get(),
        bundleNativeLibrariesDir =
            project.layout.buildDirectory.dir(
                "native-image-libs/bundle",
            ).get().asFile,
        bundleSharedLibraryFile =
            project.layout.buildDirectory.file(
                "native-image-shared/bundle/$sharedLibraryBaseName$sharedLibSuffix",
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
    project.tasks.register("${hostOsPrefix}NativeImageGenerateInteropJava") {
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
    project.tasks.register("${hostOsPrefix}NativeImageFilterComposeUberJar") {
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
    return project.tasks.register("${hostOsPrefix}NativeImageCompileInteropJava", Exec::class.java) {
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
    return project.tasks.register("${hostOsPrefix}NativeImageInteropJar", Jar::class.java) {
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
    project.tasks.register("${hostOsPrefix}NativeImageGenerateJniConfig") {
        group = "build"
        description = "Generates JNI metadata for the GraalVM shared-library experiment."
        dependsOn(filterComposeUberJar)
        inputs.file(config.filteredComposeUberJarFile)
        inputs.property("composeNativeHostNativeImageJniConfigPackages", config.generatedJniConfigPackages)
        inputs.property("composeNativeHostNativeImageJniConfigTypes", config.generatedJniConfigTypes)
        outputs.file(config.generatedJniConfigFile)

        doLast {
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
            val nativeImageTool = if (hostOsPrefix == "windows") {
                val cmdFile = File(graalHome, "bin/native-image.cmd")
                val exeFile = File(graalHome, "bin/native-image.exe")
                if (exeFile.exists()) exeFile.absolutePath else cmdFile.absolutePath
            } else {
                "$graalHome/bin/native-image"
            }

            val argsList = buildList {
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
                add("-H:JNIConfigurationFiles=${config.generatedJniConfigFile.absolutePath.replace('\\', '/')}")
                config.preservePackages.forEach { packageName ->
                    add("-H:Preserve=package=$packageName")
                }
                addAll(sharedLibraryExtraBuildArgs(config, flavor))
                add("--add-modules=org.graalvm.nativeimage")
                add("-cp")
                add(
                    listOf(config.helperJarFile, composeUberJar)
                        .joinToString(separator = File.pathSeparator) { it.absolutePath.replace('\\', '/') },
                )
                add("-o")
                add(File(sharedLibraryOutputDir, config.sharedLibraryBaseName).absolutePath.replace('\\', '/'))
                add(config.helperMainClass)
            }

            if (hostOsPrefix == "windows") {
                val isEnvActive = !System.getenv("INCLUDE").isNullOrBlank() && !System.getenv("LIB").isNullOrBlank()
                val buildCmd = buildString {
                    if (!isEnvActive) {
                        val vcvars64 = locateVcvars64(project).absolutePath
                        append("call \"")
                        append(vcvars64)
                        append("\" && ")
                    }
                    append("\"")
                    append(nativeImageTool)
                    append("\"")
                    argsList.forEach { arg ->
                        append(" \"")
                        append(arg.replace("\"", "\\\""))
                        append("\"")
                    }
                }
                commandLine("cmd.exe", "/c", buildCmd)
            } else {
                commandLine(buildList {
                    add(nativeImageTool)
                    addAll(argsList)
                })
            }
        }
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

        osName.contains("win") ->
            NativeImageHostResourceFilter(
                hostLibrarySuffixes = setOf(".dll"),
                hostOsTokens = setOf("win", "windows"),
                hostArchTokens =
                    when (osArch) {
                        "aarch64", "arm64" -> setOf("aarch64", "arm64")
                        "amd64", "x64", "x86_64" -> setOf("amd64", "x64", "x86_64")
                        else -> throw GradleException("Unsupported Windows arch for Compose Native Host native-image filtering: ${System.getProperty("os.arch")}")
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

private fun generatedNativeImageHelperMainClass(project: Project): String =
    "letmutex.compose.nativehost.generated.${sanitizeJavaIdentifier(project.name)}NativeImageEntryPoint"

private fun resolveNativeImageMainClasses(
    extension: ComposeNativeHostPluginExtension,
): List<String> =
    extension.nativeImage.mainClasses.get().filter(String::isNotBlank).distinct().ifEmpty {
        throw GradleException(
            "Configure composeNativeHost.nativeImage.mainClasses before enabling native-image support.",
        )
    }
