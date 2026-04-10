package letmutex.compose.nativehost.plugin

import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.util.zip.ZipInputStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

data class NativeLauncherConfig(
    val appName: String,
    val executableName: String,
    val javaHome: String,
    val jvmArgs: List<String>,
    val launcherSources: List<File>,
    val extractedSourcesDir: File,
    val bridgeLibraryFile: File,
    val bridgeModuleFile: File,
    val generatedSwiftSourcesDir: File,
    val jvmArgsSwiftSourceFile: File,
    val outputFile: File,
)

data class NativeBundleConfig(
    val executableName: String,
    val bundleName: String,
    val bundleIdentifier: String,
    val bundleDisplayName: String,
    val bundleVersion: String,
    val shortVersion: String,
    val minimumSystemVersion: String,
    val codeSignIdentity: String,
    val jvmTargetName: String,
    val nativeBridgeFile: File,
    val patchedRuntimeClasspathDir: File,
    val patchedRuntimeClasspathMapFile: File,
    val generatedInfoPlistDir: File,
    val stagedAppBundlesDir: File,
    val appResourcesDir: File,
    val extraBundleContents: List<ComposeNativeHostBundleContent>,
)

fun NativeBundleConfig.generatedInfoPlist(runtimeMode: ComposeNativeHostTargetRuntime): File =
    File(generatedInfoPlistDir, "${runtimeMode.taskPrefix}-Info.plist")

fun NativeBundleConfig.appBundleDir(runtimeMode: ComposeNativeHostTargetRuntime): File =
    File(stagedAppBundlesDir, runtimeMode.taskPrefix).resolve(bundleName)

internal fun ComposeNativeHostBundleContent.supports(runtimeMode: ComposeNativeHostTargetRuntime): Boolean =
    runtimeMode in runtimes

fun registerBuildNativeLauncherTask(project: Project): TaskProvider<Exec> =
    project.tasks.register("macosBuildLauncher", Exec::class.java) {
        group = "build"
        description = "Builds the native macOS launcher for Compose Native Host."
    }

fun registerBuildNativeBridgeTask(project: Project): TaskProvider<Exec> =
    project.tasks.register("macosBuildBridge", Exec::class.java) {
        group = "build"
        description = "Builds the native macOS bridge library for Compose Native Host."
    }

fun registerGenerateJvmArgsSwiftSourceTask(
    project: Project,
    config: NativeLauncherConfig,
): TaskProvider<Task> =
    project.tasks.register("generateComposeNativeHostJvmArgsSwift") {
        group = "build"
        description = "Generates shared Swift sources for Compose Native Host JVM args."
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        outputs.file(config.jvmArgsSwiftSourceFile)

        doLast {
            writeJvmArgsSwiftSource(config.jvmArgsSwiftSourceFile.parentFile, config.jvmArgs)
        }
    }

fun registerStageNativeAppBundleTask(project: Project): TaskProvider<Sync> =
    project.tasks.register("macosStageAppBundle", Sync::class.java) {
        group = "build"
        description = "Stages the native macOS app bundle for Compose Native Host."
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

fun registerStageNativeImageAppBundleTask(project: Project): TaskProvider<Sync> =
    project.tasks.register("macosNativeImageStageAppBundle", Sync::class.java) {
        group = "build"
        description = "Stages the native macOS app bundle for the native-image runtime."
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

fun registerNativeRunTask(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    project.tasks.register("macosRun") {
        group = "run"
        description = "Launches the staged native macOS app bundle."
        dependsOn(stageNativeAppBundle)
        doLast {
            launchNativeBundle(project, extension, ComposeNativeHostTargetRuntime.Jvm)
        }
    }
}

fun registerNativeImageRunTask(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    project.tasks.register("macosNativeImageRun") {
        group = "run"
        description = "Launches the staged native macOS app bundle with the native-image runtime."
        dependsOn(stageNativeAppBundle)
        doLast {
            launchNativeBundle(project, extension, ComposeNativeHostTargetRuntime.SharedLibrary)
        }
    }
}

fun registerNativeBundleInfoTask(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    project.tasks.register("macosBundleInfo") {
        group = "help"
        description = "Prints the staged app bundle path."
        dependsOn(stageNativeAppBundle)
        doLast {
            val bundleName = extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
            val bundleDir = project.layout.buildDirectory.dir("native-app/macos/$bundleName").get().asFile
            println(bundleDir.absolutePath)
        }
    }
}

fun registerNativeImageBundleInfoTask(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    project.tasks.register("macosNativeImageBundleInfo") {
        group = "help"
        description = "Prints the staged native-image app bundle path."
        dependsOn(stageNativeAppBundle)
        doLast {
            val bundleName = extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
            val bundleDir = project.layout.buildDirectory.dir("native-app/macos-native-image/$bundleName").get().asFile
            println(bundleDir.absolutePath)
        }
    }
}

fun configureBuildNativeLauncher(
    project: Project,
    config: NativeLauncherConfig,
    taskProvider: TaskProvider<Exec>,
    extractTask: TaskProvider<Task>,
    buildNativeBridge: TaskProvider<Exec>,
    generateJvmArgsSwiftSource: TaskProvider<Task>,
) {
    taskProvider.configure {
        dependsOn(extractTask, buildNativeBridge, generateJvmArgsSwiftSource)
        inputs.files(config.launcherSources)
        inputs.files(project.files(config.generatedSwiftSourcesDir).filter(File::exists))
        inputs.files(project.files(config.jvmArgsSwiftSourceFile).builtBy(generateJvmArgsSwiftSource))
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        inputs.file(config.bridgeLibraryFile)
        inputs.file(config.bridgeModuleFile)
        outputs.file(config.outputFile)

        doFirst {
            val generatedLauncherSources =
                (
                    swiftSources(config.generatedSwiftSourcesDir) +
                        listOf(config.jvmArgsSwiftSourceFile).filter(File::exists)
                ).sortedBy(File::getAbsolutePath)
            config.outputFile.parentFile.mkdirs()
            commandLine(
                buildList {
                    add("swiftc")
                    addAll(config.launcherSources.map(File::getAbsolutePath))
                    addAll(generatedLauncherSources.map(File::getAbsolutePath))
                    add("-I")
                    add(config.bridgeModuleFile.parentFile.absolutePath)
                    add("-L")
                    add(config.bridgeLibraryFile.parentFile.absolutePath)
                    add("-l$nativeBridgeLibraryName")
                    add("-Xlinker")
                    add("-rpath")
                    add("-Xlinker")
                    add("@executable_path/../Resources/native")
                    add("-o")
                    add(config.outputFile.absolutePath)
                    add("-framework")
                    add("Cocoa")
                    add("-framework")
                    add("Metal")
                    add("-I")
                    add("${config.javaHome}/include")
                    add("-I")
                    add("${config.javaHome}/include/darwin")
                },
            )
        }
    }
}

fun configureBuildNativeBridge(
    project: Project,
    config: NativeLauncherConfig,
    taskProvider: TaskProvider<Exec>,
    extractTask: TaskProvider<Task>,
    generateJvmArgsSwiftSource: TaskProvider<Task>,
) {
    taskProvider.configure {
        dependsOn(extractTask, generateJvmArgsSwiftSource)
        val bridgeSourcesDir = File(config.extractedSourcesDir, "native")
        val sharedSwiftSourcesDir = File(config.extractedSourcesDir, "swift")
        inputs.dir(bridgeSourcesDir)
        inputs.dir(sharedSwiftSourcesDir)
        inputs.files(project.files(config.generatedSwiftSourcesDir).filter(File::exists))
        inputs.files(project.files(config.jvmArgsSwiftSourceFile).builtBy(generateJvmArgsSwiftSource))
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        outputs.file(config.bridgeLibraryFile)
        outputs.file(config.bridgeModuleFile)

        doFirst {
            val bridgeSources =
                bridgeSourcesDir.listFiles()
                    ?.filter { it.isFile && (it.extension == "m" || it.extension == "h") }
                    ?.sortedBy(File::getName)
                    .orEmpty()
            val bridgeCompilationSources = bridgeSources.filter { it.extension == "m" }
            val sharedSwiftSources =
                (
                    swiftSources(sharedSwiftSourcesDir) +
                        swiftSources(config.generatedSwiftSourcesDir) +
                        listOf(config.jvmArgsSwiftSourceFile).filter(File::exists)
                )
                    .sortedBy(File::getAbsolutePath)
            check(bridgeCompilationSources.size == 1) {
                "Expected exactly one Objective-C bridge source, found ${bridgeCompilationSources.size}."
            }
            config.bridgeLibraryFile.parentFile.mkdirs()
            config.bridgeModuleFile.parentFile.mkdirs()
            val bridgeObjectFile = File(config.bridgeLibraryFile.parentFile, "Bridge.o")
            val bridgeSource = bridgeCompilationSources.single()
            commandLine(
                "/bin/zsh",
                "-lc",
                buildString {
                    append("clang -c -fobjc-arc ")
                    append("-I ")
                    append(shellQuote("${config.javaHome}/include"))
                    append(" -I ")
                    append(shellQuote("${config.javaHome}/include/darwin"))
                    append(" ")
                    append(shellQuote(bridgeSource.absolutePath))
                    append(" -o ")
                    append(shellQuote(bridgeObjectFile.absolutePath))
                    append(" && swiftc ")
                    append(sharedSwiftSources.joinToString(" ") { shellQuote(it.absolutePath) })
                    append(" ")
                    append(shellQuote(bridgeObjectFile.absolutePath))
                    append(" -emit-library -emit-module")
                    append(" -module-name ")
                    append(shellQuote(nativeBridgeModuleName))
                    append(" -emit-module-path ")
                    append(shellQuote(config.bridgeModuleFile.absolutePath))
                    append(" -Xlinker -install_name -Xlinker ")
                    append(shellQuote("@rpath/${config.bridgeLibraryFile.name}"))
                    append(" -o ")
                    append(shellQuote(config.bridgeLibraryFile.absolutePath))
                    append(" -framework Cocoa -framework Metal -framework JavaRuntimeSupport")
                },
            )
        }
    }
}

fun registerExtractNativeHostSourcesTask(
    project: Project,
    config: NativeLauncherConfig,
): TaskProvider<Task> {
    return project.tasks.register("macosExtractHostSources") {
        group = "build"
        val extractedDir = config.extractedSourcesDir
        outputs.dir(extractedDir)

        doLast {
            val zipStream = ComposeNativeHostPlugin::class.java.getResourceAsStream("/native-host-sources.zip")
                ?: throw GradleException("Could not find native-host-sources.zip. Plugin build might be broken.")

            extractedDir.deleteRecursively()
            extractedDir.mkdirs()

            ZipInputStream(zipStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(extractedDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile.mkdirs()
                        file.outputStream().use { zis.copyTo(it) }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }
}

fun configureStageNativeAppBundle(
    project: Project,
    bundleConfig: NativeBundleConfig,
    taskProvider: TaskProvider<Sync>,
    buildNativeLauncher: TaskProvider<Exec>,
    buildNativeBridge: TaskProvider<Exec>,
    preparePatchedComposeDesktopRuntimeClasspath: TaskProvider<Task>,
    runtimeMode: ComposeNativeHostTargetRuntime,
    additionalBundleContents: List<ComposeNativeHostBundleContent> = emptyList(),
) {
    val desktopJarTask = project.tasks.named("${bundleConfig.jvmTargetName}Jar", Jar::class.java)
    val launcherFile = project.layout.buildDirectory.file("bin/${bundleConfig.executableName}Launcher")
    val generatedInfoPlist = bundleConfig.generatedInfoPlist(runtimeMode)

    taskProvider.configure {
        dependsOn(
            buildNativeLauncher,
            desktopJarTask,
            buildNativeBridge,
            preparePatchedComposeDesktopRuntimeClasspath,
        )
        into(bundleConfig.appBundleDir(runtimeMode))

        configureLauncherCopy(this, launcherFile, bundleConfig.executableName)
        from(bundleConfig.nativeBridgeFile) { into("Contents/Resources/native") }
        from(generatedInfoPlist) {
            into("Contents")
            rename { "Info.plist" }
        }
        if (bundlesJvmRuntime(runtimeMode)) {
            from(desktopJarTask) { into("Contents/Resources/app/lib") }
            from(bundleConfig.patchedRuntimeClasspathDir) {
                into("Contents/Resources/app/lib")
                include("*.jar")
            }
        }
        from(bundleConfig.appResourcesDir) { into("Contents/Resources") }
        configureExtraBundleCopies(
            this,
            (bundleConfig.extraBundleContents + additionalBundleContents).filter { content ->
                content.supports(runtimeMode)
            },
        )

        doFirst {
            bundleConfig.appBundleDir(runtimeMode).deleteRecursively()
            generatedInfoPlist.parentFile.mkdirs()
            generatedInfoPlist.writeText(
                renderInfoPlist(
                    executableName = bundleConfig.executableName,
                    bundleIdentifier = bundleConfig.bundleIdentifier,
                    bundleDisplayName = bundleConfig.bundleDisplayName,
                    shortVersion = bundleConfig.shortVersion,
                    bundleVersion = bundleConfig.bundleVersion,
                    minimumSystemVersion = bundleConfig.minimumSystemVersion,
                    runtimeMode = runtimeMode,
                ),
            )
        }

        doLast {
            codesignBundle(bundleConfig, runtimeMode)
        }
    }
}

private fun bundlesJvmRuntime(runtimeMode: ComposeNativeHostTargetRuntime): Boolean = runtimeMode == ComposeNativeHostTargetRuntime.Jvm

fun configureComposeDesktopNativeDistributablePatchTask(
    taskProvider: TaskProvider<Task>,
    project: Project,
    bundleConfig: NativeBundleConfig,
    buildNativeLauncher: TaskProvider<Exec>,
    buildNativeBridge: TaskProvider<Exec>,
    preparePatchedComposeDesktopRuntimeClasspath: TaskProvider<Task>,
    composeTaskName: String,
    runtimeMode: ComposeNativeHostTargetRuntime,
    additionalBundleContents: List<ComposeNativeHostBundleContent> = emptyList(),
) {
    val launcherFile = project.layout.buildDirectory.file("bin/${bundleConfig.executableName}Launcher")
    taskProvider.configure {
        group = "distribution"
        description = "Applies the Compose Native Host bundle patch to $composeTaskName."
        dependsOn(composeTaskName, buildNativeLauncher, buildNativeBridge, preparePatchedComposeDesktopRuntimeClasspath)
        inputs.file(launcherFile)
        inputs.file(bundleConfig.nativeBridgeFile)
        inputs.files(project.files(bundleConfig.appResourcesDir).filter(File::exists))
        inputs.dir(bundleConfig.patchedRuntimeClasspathDir)
        inputs.file(bundleConfig.patchedRuntimeClasspathMapFile)
        (bundleConfig.extraBundleContents + additionalBundleContents).forEach { content ->
            dependsOn(content.files)
            inputs.files(content.files.filter(File::exists))
        }
        doLast {
            patchComposeDesktopBundle(
                project = project,
                bundleConfig = bundleConfig,
                launcherFile = launcherFile.get().asFile,
                composeTaskName = composeTaskName,
                runtimeMode = runtimeMode,
                additionalBundleContents = additionalBundleContents,
            )
        }
    }
}

fun createNativeLauncherConfig(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
): NativeLauncherConfig {
    val executableName = extension.executableName.requireValue("composeNativeHost.executableName")
    return NativeLauncherConfig(
        appName = extension.appName.requireValue("composeNativeHost.appName"),
        executableName = executableName,
        javaHome = resolveJavaHome(project),
        jvmArgs = extension.jvmArgs.getOrElse(emptyList()),
        launcherSources =
            swiftSources(
                extension.macos.launcherSourcesDir.requireValue("composeNativeHost.macos.launcherSourcesDir").asFile,
            ),
        extractedSourcesDir = project.layout.buildDirectory.dir("generated/compose-native-host/extracted-sources").get().asFile,
        bridgeLibraryFile = project.layout.buildDirectory.file("native/libcompose-native-host.dylib").get().asFile,
        bridgeModuleFile = project.layout.buildDirectory.file("native/$nativeBridgeModuleName.swiftmodule").get().asFile,
        generatedSwiftSourcesDir =
            project.layout.buildDirectory.dir("generated/compose-native-host/macos-main/swift").get().asFile,
        jvmArgsSwiftSourceFile =
            project.layout.buildDirectory.file(
                "generated/compose-native-host/shared-swift/$nativeHostJvmArgsTypeName.swift",
            ).get().asFile,
        outputFile = project.layout.buildDirectory.file("bin/${executableName}Launcher").get().asFile,
    )
}

fun createNativeBundleConfig(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
): NativeBundleConfig {
    val executableName = extension.executableName.requireValue("composeNativeHost.executableName")
    val bundleName = extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
    val jvmTargetName = extension.jvmTargetName.requireValue("composeNativeHost.jvmTargetName")
    return NativeBundleConfig(
        executableName = executableName,
        bundleName = extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName"),
        bundleIdentifier = extension.bundleIdentifier.requireValue("composeNativeHost.bundleIdentifier"),
        bundleDisplayName = extension.macos.bundleDisplayName.requireValue("composeNativeHost.macos.bundleDisplayName"),
        bundleVersion = extension.macos.bundleVersion.requireValue("composeNativeHost.macos.bundleVersion"),
        shortVersion = extension.macos.shortVersion.requireValue("composeNativeHost.macos.shortVersion"),
        minimumSystemVersion = extension.macos.minimumSystemVersion.requireValue("composeNativeHost.macos.minimumSystemVersion"),
        codeSignIdentity = extension.macos.codeSignIdentity.requireValue("composeNativeHost.macos.codeSignIdentity"),
        jvmTargetName = jvmTargetName,
        nativeBridgeFile = project.layout.buildDirectory.file("native/libcompose-native-host.dylib").get().asFile,
        patchedRuntimeClasspathDir =
            project.layout.buildDirectory.dir("generated/compose-native-host/runtime-classpath/$bundleName").get().asFile,
        patchedRuntimeClasspathMapFile =
            project.layout.buildDirectory.file("generated/compose-native-host/runtime-classpath/$bundleName/runtime-jars.map").get().asFile,
        generatedInfoPlistDir = project.layout.buildDirectory.dir("generated/native-app").get().asFile,
        stagedAppBundlesDir = project.layout.buildDirectory.dir("native-app").get().asFile,
        appResourcesDir = project.layout.projectDirectory.dir("src/${jvmTargetName}Main/resources").asFile,
        extraBundleContents = extension.macos.bundleContents(),
    )
}

fun configureLauncherCopy(
    task: Sync,
    launcherFile: Provider<RegularFile>,
    executableName: String,
) {
    task.from(launcherFile) {
        into("Contents/MacOS")
        rename { executableName }
        filePermissions {
            user {
                read = true
                write = true
                execute = true
            }
            group {
                read = true
                execute = true
            }
            other {
                read = true
                execute = true
            }
        }
    }
}

private fun configureExtraBundleCopies(
    task: Sync,
    bundleContents: List<ComposeNativeHostBundleContent>,
) {
    bundleContents.forEachIndexed { index, content ->
        task.from(content.files) {
            into(content.into)
            if (content.executable) {
                filePermissions {
                    user {
                        read = true
                        write = true
                        execute = true
                    }
                    group {
                        read = true
                        execute = true
                    }
                    other {
                        read = true
                        execute = true
                    }
                }
            }
        }
        task.inputs.files(content.files).withPropertyName("composeNativeHostExtraBundleContent$index")
    }
}

private fun launchNativeBundle(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    runtimeMode: ComposeNativeHostTargetRuntime,
) {
    val executableName = extension.executableName.requireValue("composeNativeHost.executableName")
    val bundleName = extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
    val launcher =
        project.layout.buildDirectory
            .dir("native-app/${runtimeMode.taskPrefix}/$bundleName/Contents/MacOS")
            .get()
            .file(executableName)
            .asFile

    println("Launching native host bundle: ${launcher.absolutePath}")
    val process =
        ProcessBuilder(launcher.absolutePath)
            .apply {
                if (runtimeMode == ComposeNativeHostTargetRuntime.Jvm) {
                    environment().putIfAbsent("JAVA_HOME", resolveJavaHome(project))
                }
            }.start()
    val stdoutThread = relayStream(process.inputStream, System.out)
    val stderrThread = relayStream(process.errorStream, System.err)
    val shutdownHook =
        Thread {
            if (process.isAlive) {
                destroyProcessTree(process)
            }
        }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    try {
        process.waitFor()
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
    } catch (e: InterruptedException) {
        destroyProcessTree(process)
        Thread.currentThread().interrupt()
        throw e
    } finally {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
        }
    }
}

private fun codesignBundle(
    config: NativeBundleConfig,
    runtimeMode: ComposeNativeHostTargetRuntime,
) {
    val process =
        ProcessBuilder(
            "codesign",
            "--force",
            "--deep",
            "--sign",
            config.codeSignIdentity,
            config.appBundleDir(runtimeMode).absolutePath,
        ).inheritIO()
            .start()
    check(process.waitFor() == 0) {
        "Failed to codesign ${config.appBundleDir(runtimeMode).absolutePath}"
    }
}

private fun resolveJavaHome(project: Project): String =
    project.providers.gradleProperty("org.gradle.java.home")
        .orElse(project.providers.environmentVariable("JAVA_HOME"))
        .orElse(project.providers.environmentVariable("GRAALVM_HOME"))
        .orNull
        ?: throw GradleException("JAVA_HOME is required to build the native host launcher.")

private fun swiftSources(directory: File): List<File> {
    if (!directory.exists()) {
        return emptyList()
    }
    return directory.listFiles()
        ?.filter { it.isFile && it.extension == "swift" }
        ?.sortedBy(File::getName)
        .orEmpty()
}

private const val nativeBridgeModuleName = "ComposeNativeHost"
private const val nativeBridgeLibraryName = "compose-native-host"
private const val nativeHostJvmArgsTypeName = "ComposeNativeHostJvmArgs"

private fun shellQuote(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"

private fun writeJvmArgsSwiftSource(
    outputDir: File,
    jvmArgs: List<String>,
) {
    val outputFile = File(outputDir, "$nativeHostJvmArgsTypeName.swift")
    outputFile.parentFile.mkdirs()
    outputFile.writeText(
        buildString {
            appendLine("import Foundation")
            appendLine()
            appendLine("enum $nativeHostJvmArgsTypeName {")
            appendLine("    static let values: [String] = [")
            jvmArgs.forEach { arg ->
                appendLine("        ${swiftStringLiteral(arg)},")
            }
            appendLine("    ]")
            appendLine("}")
        }
    )
}

private fun swiftStringLiteral(value: String): String =
    buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

private fun renderInfoPlist(
    executableName: String,
    bundleIdentifier: String,
    bundleDisplayName: String,
    shortVersion: String,
    bundleVersion: String,
    minimumSystemVersion: String,
    runtimeMode: ComposeNativeHostTargetRuntime,
): String =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
      <key>CFBundleDevelopmentRegion</key>
      <string>en</string>
      <key>CFBundleExecutable</key>
      <string>${xmlString(executableName)}</string>
      <key>CFBundleIdentifier</key>
      <string>${xmlString(bundleIdentifier)}</string>
      <key>CFBundleInfoDictionaryVersion</key>
      <string>6.0</string>
      <key>CFBundleName</key>
      <string>${xmlString(bundleDisplayName)}</string>
      <key>CFBundlePackageType</key>
      <string>APPL</string>
      <key>CFBundleShortVersionString</key>
      <string>${xmlString(shortVersion)}</string>
      <key>CFBundleVersion</key>
      <string>${xmlString(bundleVersion)}</string>
      <key>LSMinimumSystemVersion</key>
      <string>${xmlString(minimumSystemVersion)}</string>
      <key>NSHighResolutionCapable</key>
      <true/>
      <key>ComposeNativeHostRuntimeMode</key>
      <string>${xmlString(runtimeMode.plistValue)}</string>
    </dict>
    </plist>
    """.trimIndent() + "\n"

private fun xmlString(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun destroyProcessTree(process: Process) {
    val handle = process.toHandle()
    handle.descendants().toList().asReversed().forEach { child ->
        child.destroy()
        if (child.isAlive) {
            child.destroyForcibly()
        }
    }
    handle.destroy()
    if (handle.isAlive) {
        handle.destroyForcibly()
    }
}

private fun relayStream(
    input: InputStream,
    output: PrintStream,
): Thread =
    Thread {
        input.bufferedReader().useLines { lines ->
            lines.forEach(output::println)
        }
    }.apply {
        isDaemon = true
        start()
    }
