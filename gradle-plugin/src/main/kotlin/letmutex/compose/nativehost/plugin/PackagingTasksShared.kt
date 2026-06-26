package letmutex.compose.nativehost.plugin

import java.io.File
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
            val bundleDir = project.layout.buildDirectory.dir("native-app/${hostOsPrefix}NativeImage/$bundleName").get().asFile
            println(bundleDir.absolutePath)
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
                        if (runtimeMode == ComposeNativeHostTargetRuntime.SharedLibrary) {
                            appendLine("[Runtime]")
                            appendLine("runtime.mode=sharedLibrary")
                            val dllFile = additionalBundleContents.firstOrNull()?.files?.firstOrNull { it.name.endsWith(".dll") }
                            val dllName = dllFile?.name ?: "libcompose-native-host-runtime.dll"
                            appendLine("runtime.library=native/$dllName")
                        }
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
