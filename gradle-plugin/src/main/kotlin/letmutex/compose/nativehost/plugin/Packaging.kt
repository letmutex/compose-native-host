package letmutex.compose.nativehost.plugin

import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.util.Locale
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
    val jvmArgs: List<String>,
    val mainClass: String,
)

fun NativeBundleConfig.generatedInfoPlist(runtimeMode: ComposeNativeHostTargetRuntime): File =
    File(generatedInfoPlistDir, "${runtimeMode.taskPrefix}-Info.plist")

fun NativeBundleConfig.appBundleDir(runtimeMode: ComposeNativeHostTargetRuntime): File =
    File(stagedAppBundlesDir, runtimeMode.taskPrefix).resolve(bundleName)

internal fun ComposeNativeHostBundleContent.supports(runtimeMode: ComposeNativeHostTargetRuntime): Boolean =
    runtimeMode in runtimes

fun registerBuildNativeLauncherTask(project: Project): TaskProvider<Exec> =
    project.tasks.register("${hostOsPrefix}BuildLauncher", Exec::class.java) {
        group = "build"
        description = "Builds the native host launcher for Compose Native Host."
    }

fun registerBuildNativeBridgeTask(project: Project): TaskProvider<Exec> =
    project.tasks.register("${hostOsPrefix}BuildBridge", Exec::class.java) {
        group = "build"
        description = "Builds the native host bridge library for Compose Native Host."
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
    project.tasks.register("${hostOsPrefix}StageAppBundle", Sync::class.java) {
        group = "build"
        description = "Stages the native app bundle for Compose Native Host."
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

fun registerStageNativeImageAppBundleTask(project: Project): TaskProvider<Sync> =
    project.tasks.register("${hostOsPrefix}NativeImageStageAppBundle", Sync::class.java) {
        group = "build"
        description = "Stages the native app bundle for the native-image runtime."
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

fun registerNativeRunTask(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    project.tasks.register("${hostOsPrefix}Run") {
        group = "run"
        description = "Launches the staged native app."
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
    project.tasks.register("${hostOsPrefix}NativeImageRun") {
        group = "run"
        description = "Launches the staged native app with the native-image runtime."
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
    project.tasks.register("${hostOsPrefix}BundleInfo") {
        group = "help"
        description = "Prints the staged app path."
        dependsOn(stageNativeAppBundle)
        doLast {
            val bundleName = if (hostOsPrefix == "windows") {
                extension.executableName.requireValue("composeNativeHost.executableName")
            } else {
                extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
            }
            val bundleDir = project.layout.buildDirectory.dir("native-app/${hostOsPrefix}/$bundleName").get().asFile
            println(bundleDir.absolutePath)
        }
    }
}

fun registerNativeImageBundleInfoTask(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    project.tasks.register("${hostOsPrefix}NativeImageBundleInfo") {
        group = "help"
        description = "Prints the staged native-image app path."
        dependsOn(stageNativeAppBundle)
        doLast {
            val bundleName = if (hostOsPrefix == "windows") {
                extension.executableName.requireValue("composeNativeHost.executableName")
            } else {
                extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
            }
            val bundleDir = project.layout.buildDirectory.dir("native-app/${hostOsPrefix}-native-image/$bundleName").get().asFile
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
        if (hostOsPrefix == "windows") {
            dependsOn(extractTask, generateJvmArgsSwiftSource)
        } else {
            dependsOn(extractTask, buildNativeBridge, generateJvmArgsSwiftSource)
        }
        val trackedLauncherSources = if (hostOsPrefix == "windows") {
            project.files(config.launcherSources) + project.files(File(config.extractedSourcesDir, "cpp"))
        } else {
            project.files(config.launcherSources)
        }
        inputs.files(trackedLauncherSources)
        inputs.files(project.files(config.generatedSwiftSourcesDir).filter(File::exists))
        inputs.files(project.files(config.jvmArgsSwiftSourceFile).builtBy(generateJvmArgsSwiftSource))
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        if (hostOsPrefix != "windows") {
            inputs.file(config.bridgeLibraryFile)
            inputs.file(config.bridgeModuleFile)
        }
        outputs.file(config.outputFile)

        doFirst {
            config.outputFile.parentFile.mkdirs()
            if (hostOsPrefix == "windows") {
                val isEnvActive = !System.getenv("INCLUDE").isNullOrBlank() && !System.getenv("LIB").isNullOrBlank()
                val jniHeadersDir = File(config.javaHome, "include").absolutePath
                val jniWinHeadersDir = File(config.javaHome, "include/win32").absolutePath
                val objDir = project.layout.buildDirectory.dir("tmp/native-obj/launcher").get().asFile
                objDir.mkdirs()
                val compileCmd = buildString {
                    if (!isEnvActive) {
                        val vcvars64 = locateVcvars64(project).absolutePath
                        append("call \"")
                        append(vcvars64)
                        append("\" && ")
                    }
                    append("cl.exe /nologo /EHsc /O2 ")
                    val sources = if (hostOsPrefix == "windows") {
                        config.launcherSources + cppSources(File(config.extractedSourcesDir, "cpp"))
                    } else {
                        config.launcherSources
                    }
                    sources.forEach { source ->
                        append("\"")
                        append(source.absolutePath)
                        append("\" ")
                    }
                    append("/Fe:\"")
                    append(config.outputFile.absolutePath)
                    append("\" ")
                    append("/Fo:\"")
                    append(objDir.absolutePath)
                    append("/\" ")
                    append("/Fd:\"")
                    append(objDir.absolutePath)
                    append("/\" ")
                    append("/I\"")
                    append(File(config.extractedSourcesDir, "cpp").absolutePath)
                    append("\" ")
                    append("/I\"")
                    append(File(config.extractedSourcesDir, "native/windows").absolutePath)
                    append("\" ")
                    append("/I\"")
                    append(jniHeadersDir)
                    append("\" /I\"")
                    append(jniWinHeadersDir)
                    append("\" ")
                    append("/link /nologo ")
                    val manifestFile = File(objDir, "app.manifest")
                    manifestFile.writeText(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<assembly manifestVersion=\"1.0\" xmlns=\"urn:schemas-microsoft-com:asm.v1\" xmlns:asmv3=\"urn:schemas-microsoft-com:asm.v3\">\n" +
                        "    <dependency>\n" +
                        "        <dependentAssembly>\n" +
                        "            <assemblyIdentity type=\"win32\" name=\"Microsoft.Windows.Common-Controls\"\n" +
                        "                version=\"6.0.0.0\" processorArchitecture=\"*\"\n" +
                        "                publicKeyToken=\"6595b64144ccf1df\" language=\"*\"/>\n" +
                        "        </dependentAssembly>\n" +
                        "    </dependency>\n" +
                        "    <compatibility xmlns=\"urn:schemas-microsoft-com:compatibility.v1\">\n" +
                        "        <application>\n" +
                        "            <!-- Windows 10 and Windows 11 -->\n" +
                        "            <supportedOS Id=\"{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}\"/>\n" +
                        "            <!-- Windows 8.1 -->\n" +
                        "            <supportedOS Id=\"{1f676c76-80e1-4239-95bb-83d0f6d0da78}\"/>\n" +
                        "            <!-- Windows 8 -->\n" +
                        "            <supportedOS Id=\"{4a2f28e3-53b9-4441-ba9c-d69d4a4a6e38}\"/>\n" +
                        "            <!-- Windows 7 -->\n" +
                        "            <supportedOS Id=\"{35138b9a-5d96-4fbd-8e2d-a2440225f93a}\"/>\n" +
                        "        </application>\n" +
                        "    </compatibility>\n" +
                        "</assembly>\n"
                    )
                    append("/MANIFEST:EMBED /MANIFESTINPUT:\"")
                    append(manifestFile.absolutePath)
                    append("\" ")
                    append("/IMPLIB:\"")
                    append(File(objDir, config.outputFile.nameWithoutExtension + ".lib").absolutePath)
                    append("\" ")
                    append("user32.lib gdi32.lib d3d12.lib dxgi.lib uxtheme.lib")
                }
                commandLine("cmd.exe", "/c", compileCmd)
            } else {
                val generatedLauncherSources =
                    (
                        swiftSources(config.generatedSwiftSourcesDir) +
                            listOf(config.jvmArgsSwiftSourceFile).filter(File::exists)
                    ).sortedBy(File::getAbsolutePath)
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
}

fun configureBuildNativeBridge(
    project: Project,
    config: NativeLauncherConfig,
    taskProvider: TaskProvider<Exec>,
    extractTask: TaskProvider<Task>,
    generateJvmArgsSwiftSource: TaskProvider<Task>,
    buildNativeLauncher: TaskProvider<Exec>,
) {
    taskProvider.configure {
        if (hostOsPrefix == "windows") {
            dependsOn(extractTask, generateJvmArgsSwiftSource, buildNativeLauncher)
        } else {
            dependsOn(extractTask, generateJvmArgsSwiftSource)
        }
        val bridgeSourcesDir = File(config.extractedSourcesDir, "native/$hostOsPrefix")
        val sharedSwiftSourcesDir = File(config.extractedSourcesDir, "swift")
        inputs.dir(bridgeSourcesDir)
        inputs.dir(sharedSwiftSourcesDir)
        inputs.files(project.files(config.generatedSwiftSourcesDir).filter(File::exists))
        inputs.files(project.files(config.jvmArgsSwiftSourceFile).builtBy(generateJvmArgsSwiftSource))
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        outputs.file(config.bridgeLibraryFile)
        outputs.file(config.bridgeModuleFile)

        doFirst {
            config.bridgeLibraryFile.parentFile.mkdirs()
            config.bridgeModuleFile.parentFile.mkdirs()
            if (hostOsPrefix == "windows") {
                val isEnvActive = !System.getenv("INCLUDE").isNullOrBlank() && !System.getenv("LIB").isNullOrBlank()
                val jniHeadersDir = File(config.javaHome, "include").absolutePath
                val jniWinHeadersDir = File(config.javaHome, "include/win32").absolutePath
                val launcherObjDir = project.layout.buildDirectory.dir("tmp/native-obj/launcher").get().asFile
                val launcherLib = File(launcherObjDir, config.outputFile.nameWithoutExtension + ".lib").absolutePath
                val objDir = project.layout.buildDirectory.dir("tmp/native-obj/bridge").get().asFile
                objDir.mkdirs()
                
                val compileCmd = buildString {
                    if (!isEnvActive) {
                        val vcvars64 = locateVcvars64(project).absolutePath
                        append("call \"")
                        append(vcvars64)
                        append("\" && ")
                    }
                    append("cl.exe /nologo /EHsc /O2 /LD ")
                    append("\"")
                    append(File(bridgeSourcesDir, "Bridge.cpp").absolutePath)
                    append("\" ")
                    append("/Fe:\"")
                    append(config.bridgeLibraryFile.absolutePath)
                    append("\" ")
                    append("/Fo:\"")
                    append(objDir.absolutePath)
                    append("/\" ")
                    append("/Fd:\"")
                    append(objDir.absolutePath)
                    append("/\" ")
                    append("/I\"")
                    append(jniHeadersDir)
                    append("\" /I\"")
                    append(jniWinHeadersDir)
                    append("\" /I\"")
                    append(File(config.extractedSourcesDir, "native/windows").absolutePath)
                    append("\" /I\"")
                    append(File(config.extractedSourcesDir, "cpp").absolutePath)
                    append("\" ")
                    append("\"")
                    append(launcherLib)
                    append("\" ")
                    append("/link /nologo ")
                    append("/IMPLIB:\"")
                    append(File(objDir, "bridge.lib").absolutePath)
                    append("\"")
                }
                commandLine("cmd.exe", "/c", compileCmd)
            } else {
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
                        append(" -framework Cocoa -framework Metal")
                    },
                )
            }
        }
    }
}

fun registerExtractNativeHostSourcesTask(
    project: Project,
    config: NativeLauncherConfig,
): TaskProvider<Task> {
    return project.tasks.register("${hostOsPrefix}ExtractHostSources") {
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
    val launcherFile = if (hostOsPrefix == "windows") {
        project.layout.buildDirectory.file("bin/${bundleConfig.executableName}.exe")
    } else {
        project.layout.buildDirectory.file("bin/${bundleConfig.executableName}Launcher")
    }
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
        from(bundleConfig.nativeBridgeFile) {
            if (hostOsPrefix == "windows") {
                into("native")
                rename { "bridge.dll" }
            } else {
                into("Contents/Resources/native")
            }
        }
        if (hostOsPrefix != "windows") {
            from(generatedInfoPlist) {
                into("Contents")
                rename { "Info.plist" }
            }
        }
        if (bundlesJvmRuntime(runtimeMode)) {
            from(desktopJarTask) {
                if (hostOsPrefix == "windows") {
                    into("app/lib")
                } else {
                    into("Contents/Resources/app/lib")
                }
            }
            from(bundleConfig.patchedRuntimeClasspathDir) {
                if (hostOsPrefix == "windows") {
                    into("app/lib")
                } else {
                    into("Contents/Resources/app/lib")
                }
                include("*.jar")
            }
        }
        from(bundleConfig.appResourcesDir) {
            if (hostOsPrefix == "windows") {
                into("app/resources")
            } else {
                into("Contents/Resources")
            }
        }
        configureExtraBundleCopies(
            this,
            (bundleConfig.extraBundleContents + additionalBundleContents).filter { content ->
                content.supports(runtimeMode)
            },
        )

        doFirst {
            bundleConfig.appBundleDir(runtimeMode).deleteRecursively()
            if (hostOsPrefix != "windows") {
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
        }

        doLast {
            if (hostOsPrefix == "windows") {
                val configFile = File(bundleConfig.appBundleDir(runtimeMode), "${bundleConfig.executableName}.cfg")
                configFile.parentFile.mkdirs()
                configFile.writeText(
                    buildString {
                        appendLine("[JavaOptions]")
                        appendLine("java-options=-Dcompose.native.host.bridge.path=\$APPDIR\\native\\bridge.dll")
                        bundleConfig.jvmArgs.forEach { arg ->
                            appendLine("java-options=$arg")
                        }
                        appendLine("app.mainclass=${bundleConfig.mainClass}")
                    }
                )
            } else {
                codesignBundle(bundleConfig, runtimeMode)
            }
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
    val launcherFile = if (hostOsPrefix == "windows") {
        project.layout.buildDirectory.file("bin/${bundleConfig.executableName}.exe")
    } else {
        project.layout.buildDirectory.file("bin/${bundleConfig.executableName}Launcher")
    }
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
    val extractedSourcesDir = project.layout.buildDirectory.dir("generated/compose-native-host/extracted-sources").get().asFile
    return NativeLauncherConfig(
        appName = extension.appName.requireValue("composeNativeHost.appName"),
        executableName = executableName,
        javaHome = resolveJavaHome(project),
        jvmArgs = extension.jvmArgs.getOrElse(emptyList()),
        launcherSources = if (hostOsPrefix == "windows") {
            val userDir = extension.windows.launcherSourcesDir.requireValue("composeNativeHost.windows.launcherSourcesDir").asFile
            val userSources = cppSources(userDir)
            if (userSources.isNotEmpty()) {
                userSources
            } else {
                cppSources(File(extractedSourcesDir, "cpp"))
            }
        } else {
            swiftSources(
                extension.macos.launcherSourcesDir.requireValue("composeNativeHost.macos.launcherSourcesDir").asFile,
            )
        },
        extractedSourcesDir = extractedSourcesDir,
        bridgeLibraryFile = if (hostOsPrefix == "windows") {
            project.layout.buildDirectory.file("native/bridge.dll").get().asFile
        } else {
            project.layout.buildDirectory.file("native/libcompose-native-host.dylib").get().asFile
        },
        bridgeModuleFile = project.layout.buildDirectory.file("native/$nativeBridgeModuleName.swiftmodule").get().asFile,
        generatedSwiftSourcesDir =
            project.layout.buildDirectory.dir("generated/compose-native-host/macos-main/swift").get().asFile,
        jvmArgsSwiftSourceFile =
            project.layout.buildDirectory.file(
                "generated/compose-native-host/shared-swift/$nativeHostJvmArgsTypeName.swift",
            ).get().asFile,
        outputFile = if (hostOsPrefix == "windows") {
            project.layout.buildDirectory.file("bin/${executableName}.exe").get().asFile
        } else {
            project.layout.buildDirectory.file("bin/${executableName}Launcher").get().asFile
        },
    )
}

fun createNativeBundleConfig(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
): NativeBundleConfig {
    val executableName = extension.executableName.requireValue("composeNativeHost.executableName")
    val bundleName = if (hostOsPrefix == "windows") executableName else extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
    val jvmTargetName = extension.jvmTargetName.requireValue("composeNativeHost.jvmTargetName")
    val mainClass = resolveMainClass(project, extension)
    return NativeBundleConfig(
        executableName = executableName,
        bundleName = bundleName,
        bundleIdentifier = if (hostOsPrefix == "windows") "" else extension.bundleIdentifier.requireValue("composeNativeHost.bundleIdentifier"),
        bundleDisplayName = if (hostOsPrefix == "windows") "" else extension.macos.bundleDisplayName.requireValue("composeNativeHost.macos.bundleDisplayName"),
        bundleVersion = if (hostOsPrefix == "windows") "" else extension.macos.bundleVersion.requireValue("composeNativeHost.macos.bundleVersion"),
        shortVersion = if (hostOsPrefix == "windows") "" else extension.macos.shortVersion.requireValue("composeNativeHost.macos.shortVersion"),
        minimumSystemVersion = if (hostOsPrefix == "windows") "" else extension.macos.minimumSystemVersion.requireValue("composeNativeHost.macos.minimumSystemVersion"),
        codeSignIdentity = if (hostOsPrefix == "windows") "" else extension.macos.codeSignIdentity.requireValue("composeNativeHost.macos.codeSignIdentity"),
        jvmTargetName = jvmTargetName,
        nativeBridgeFile = if (hostOsPrefix == "windows") {
            project.layout.buildDirectory.file("native/bridge.dll").get().asFile
        } else {
            project.layout.buildDirectory.file("native/libcompose-native-host.dylib").get().asFile
        },
        patchedRuntimeClasspathDir =
            project.layout.buildDirectory.dir("generated/compose-native-host/runtime-classpath/$bundleName").get().asFile,
        patchedRuntimeClasspathMapFile =
            project.layout.buildDirectory.file("generated/compose-native-host/runtime-classpath/$bundleName/runtime-jars.map").get().asFile,
        generatedInfoPlistDir = project.layout.buildDirectory.dir("generated/native-app").get().asFile,
        stagedAppBundlesDir = project.layout.buildDirectory.dir("native-app").get().asFile,
        appResourcesDir = project.layout.projectDirectory.dir("src/${jvmTargetName}Main/resources").asFile,
        extraBundleContents = if (hostOsPrefix == "windows") emptyList() else extension.macos.bundleContents(),
        jvmArgs = extension.jvmArgs.getOrElse(emptyList()),
        mainClass = mainClass,
    )
}

private fun resolveMainClass(project: Project, extension: ComposeNativeHostPluginExtension): String {
    val extMainClass = extension.nativeImage.mainClasses.getOrElse(emptyList()).firstOrNull { it.isNotBlank() }
    if (extMainClass != null) {
        return extMainClass
    }
    try {
        val composeExtension = project.extensions.findByName("compose")
        if (composeExtension != null) {
            val desktop = composeExtension.javaClass.getMethod("getDesktop").invoke(composeExtension)
            val application = desktop.javaClass.getMethod("getApplication").invoke(desktop)
            val mainClassProp = application.javaClass.getMethod("getMainClass").invoke(application)
            if (mainClassProp is org.gradle.api.provider.Property<*>) {
                val value = mainClassProp.orNull
                if (value is String && value.isNotBlank()) {
                    return value
                }
            } else if (mainClassProp is String && mainClassProp.isNotBlank()) {
                return mainClassProp
            }
        }
    } catch (_: Exception) {
    }
    return "letmutex.compose.nativehost.sample.MainKt"
}

fun configureLauncherCopy(
    task: Sync,
    launcherFile: Provider<RegularFile>,
    executableName: String,
) {
    task.from(launcherFile) {
        if (hostOsPrefix == "windows") {
            into(".")
            rename { "$executableName.exe" }
        } else {
            into("Contents/MacOS")
            rename { executableName }
        }
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
            into(mapStagedPath(content.into))
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
    val bundleName = if (hostOsPrefix == "windows") executableName else extension.macos.bundleName.requireValue("composeNativeHost.macos.bundleName")
    val launcher = if (hostOsPrefix == "windows") {
        project.layout.buildDirectory
            .dir("native-app/${runtimeMode.taskPrefix}/$bundleName")
            .get()
            .file("$executableName.exe")
            .asFile
    } else {
        project.layout.buildDirectory
            .dir("native-app/${runtimeMode.taskPrefix}/$bundleName/Contents/MacOS")
            .get()
            .file(executableName)
            .asFile
    }

    println("Launching native host: ${launcher.absolutePath}")
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

private fun locateVcvars64(project: Project): File {
    val propPath = project.providers.gradleProperty("vcvars64Path").orNull
    if (!propPath.isNullOrBlank()) {
        val f = File(propPath)
        if (f.exists()) return f
    }

    val envPath = project.providers.environmentVariable("VCVARS64_PATH").orNull
    if (!envPath.isNullOrBlank()) {
        val f = File(envPath)
        if (f.exists()) return f
    }

    var vswhere = File("C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe")
    if (!vswhere.exists()) {
        val pathEnv = System.getenv("PATH").orEmpty()
        val paths = pathEnv.split(File.pathSeparatorChar)
        val vswhereInPath = paths.map { File(it, "vswhere.exe") }.firstOrNull { it.exists() }
        if (vswhereInPath != null) {
            vswhere = vswhereInPath
        } else {
            throw GradleException(
                "Visual Studio Installer (vswhere.exe) not found at default path or in PATH. " +
                "Please configure 'vcvars64Path' property or 'VCVARS64_PATH' environment variable."
            )
        }
    }

    val process = ProcessBuilder(
        vswhere.absolutePath,
        "-latest",
        "-property",
        "installationPath"
    ).redirectError(ProcessBuilder.Redirect.PIPE)
     .redirectOutput(ProcessBuilder.Redirect.PIPE)
     .start()

    val path = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()

    if (path.isBlank()) {
        throw GradleException("Could not locate Visual Studio installation using vswhere.exe")
    }

    val vcvars64 = File(path, "VC\\Auxiliary\\Build\\vcvars64.bat")
    if (!vcvars64.exists()) {
        throw GradleException("Could not find vcvars64.bat at: ${vcvars64.absolutePath}")
    }

    return vcvars64
}

private fun cppSources(directory: File): List<File> {
    if (!directory.exists()) {
        return emptyList()
    }
    return directory.listFiles()
        ?.filter { it.isFile && (it.extension == "cpp" || it.extension == "c" || it.extension == "cc") }
        ?.sortedBy(File::getName)
        .orEmpty()
}

private fun mapStagedPath(intoPath: String): String {
    if (hostOsPrefix != "windows") return intoPath
    return when {
        intoPath.startsWith("Contents/Resources/native") -> "native"
        intoPath.startsWith("Contents/Resources") -> "app/resources"
        intoPath.startsWith("Contents/app") -> "app"
        else -> intoPath
    }
}

