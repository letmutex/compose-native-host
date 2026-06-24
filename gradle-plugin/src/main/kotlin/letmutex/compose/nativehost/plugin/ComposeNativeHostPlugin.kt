package letmutex.compose.nativehost.plugin

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

class ComposeNativeHostPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = createExtension(project)
        val buildNativeLauncher = registerBuildNativeLauncherTask(project)
        val buildNativeBridge = registerBuildNativeBridgeTask(project)
        val stageNativeAppBundle = registerStageNativeAppBundleTask(project)
        registerNativeDistributableTaskSkeletons(project, jvmDistributableSpecs)
        registerNativeRunTask(project, extension, stageNativeAppBundle)
        registerNativeBundleInfoTask(project, extension, stageNativeAppBundle)
        configureTasksAfterEvaluate(project, extension, buildNativeLauncher, buildNativeBridge, stageNativeAppBundle)
    }
}

private fun configureTasksAfterEvaluate(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    buildNativeLauncher: TaskProvider<Exec>,
    buildNativeBridge: TaskProvider<Exec>,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    project.afterEvaluate {
        val nativeImageBundleContents = configureNativeImageSupport(project, extension)

        val launcherConfig = createNativeLauncherConfig(project, extension)
        val bundleConfig = createNativeBundleConfig(project, extension)
        val extractTask = registerExtractNativeHostSourcesTask(project, launcherConfig)
        val generateJvmArgsSwiftSource = registerGenerateJvmArgsSwiftSourceTask(project, launcherConfig)
        val preparePatchedComposeDesktopRuntimeClasspath =
            registerPreparePatchedComposeDesktopRuntimeClasspathTask(project, bundleConfig)

        if (hostOsPrefix == "windows") {
            configureWindowsNativeLauncher(
                project = project,
                config = launcherConfig,
                taskProvider = buildNativeLauncher,
                extractTask = extractTask,
                generateJvmArgsSwiftSource = generateJvmArgsSwiftSource,
            )
            configureWindowsNativeBridge(
                project = project,
                config = launcherConfig,
                taskProvider = buildNativeBridge,
                extractTask = extractTask,
                generateJvmArgsSwiftSource = generateJvmArgsSwiftSource,
                buildNativeLauncher = buildNativeLauncher,
            )
        } else {
            configureMacOsNativeLauncher(
                project = project,
                config = launcherConfig,
                taskProvider = buildNativeLauncher,
                extractTask = extractTask,
                buildNativeBridge = buildNativeBridge,
                generateJvmArgsSwiftSource = generateJvmArgsSwiftSource,
            )
            configureMacOsNativeBridge(
                project = project,
                config = launcherConfig,
                taskProvider = buildNativeBridge,
                extractTask = extractTask,
                generateJvmArgsSwiftSource = generateJvmArgsSwiftSource,
            )
        }
        configureJvmBundleTasks(
            project = project,
            bundleConfig = bundleConfig,
            buildNativeLauncher = buildNativeLauncher,
            buildNativeBridge = buildNativeBridge,
            preparePatchedComposeDesktopRuntimeClasspath = preparePatchedComposeDesktopRuntimeClasspath,
            stageNativeAppBundle = stageNativeAppBundle,
        )
        configureNativeImageBundleTasks(
            project = project,
            extension = extension,
            bundleConfig = bundleConfig,
            buildNativeLauncher = buildNativeLauncher,
            buildNativeBridge = buildNativeBridge,
            preparePatchedComposeDesktopRuntimeClasspath = preparePatchedComposeDesktopRuntimeClasspath,
            additionalBundleContents = nativeImageBundleContents,
        )
    }
}

private fun configureNativeImageSupport(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
): NativeImageBundleContents {
    if (!extension.nativeImage.isConfigured()) {
        return NativeImageBundleContents()
    }
    val nativeImageConfig = createNativeImageExperimentConfig(project, extension)
    val nativeImageFilterComposeUberJar =
        registerNativeImageFilterComposeUberJarTask(project, nativeImageConfig)
    val nativeImageGenerateInteropJava =
        registerNativeImageGenerateInteropJavaTask(project, nativeImageConfig)
    val nativeImageCompileInteropJava =
        registerNativeImageCompileInteropJavaTask(
            project,
            nativeImageConfig,
            nativeImageGenerateInteropJava,
            nativeImageFilterComposeUberJar,
        )
    val nativeImageInteropJar =
        registerNativeImageInteropJarTask(project, nativeImageConfig, nativeImageCompileInteropJava)
    val nativeImageGenerateJniConfig =
        registerNativeImageGenerateJniConfigTask(project, nativeImageConfig, nativeImageFilterComposeUberJar)
    val nativeImageExtractDevLibraries =
        registerNativeImageExtractHostLibrariesTask(
            project,
            nativeImageConfig,
            nativeImageFilterComposeUberJar,
            NativeImageSharedLibraryFlavor.Dev,
        )
    val nativeImageExtractBundleLibraries =
        registerNativeImageExtractHostLibrariesTask(
            project,
            nativeImageConfig,
            nativeImageFilterComposeUberJar,
            NativeImageSharedLibraryFlavor.Bundle,
        )
    val nativeImageBuildDevSharedLibrary =
        registerNativeImageBuildSharedLibraryTask(
            project,
            nativeImageConfig,
            nativeImageInteropJar,
            nativeImageGenerateJniConfig,
            NativeImageSharedLibraryFlavor.Dev,
        )
    val nativeImageBuildBundleSharedLibrary =
        registerNativeImageBuildSharedLibraryTask(
            project,
            nativeImageConfig,
            nativeImageInteropJar,
            nativeImageGenerateJniConfig,
            NativeImageSharedLibraryFlavor.Bundle,
        )
    registerNativeDistributableTaskSkeletons(project, nativeImageDistributableSpecs)
    val devBundleContents =
        nativeImageBundleContents(
            project = project,
            runtimeFile = nativeImageConfig.devSharedLibraryFile,
            runtimeBuiltBy = nativeImageBuildDevSharedLibrary,
            nativeLibrariesDir = nativeImageConfig.devNativeLibrariesDir,
            nativeLibrariesBuiltBy = nativeImageExtractDevLibraries,
        )
    val bundleBundleContents =
        nativeImageBundleContents(
            project = project,
            runtimeFile = nativeImageConfig.bundleSharedLibraryFile,
            runtimeBuiltBy = nativeImageBuildBundleSharedLibrary,
            nativeLibrariesDir = nativeImageConfig.bundleNativeLibrariesDir,
            nativeLibrariesBuiltBy = nativeImageExtractBundleLibraries,
        )
    return NativeImageBundleContents(
        dev = devBundleContents,
        bundle = bundleBundleContents,
    )
}

private fun nativeImageBundleContents(
    project: Project,
    runtimeFile: File,
    runtimeBuiltBy: Any,
    nativeLibrariesDir: File,
    nativeLibrariesBuiltBy: Any,
): List<ComposeNativeHostBundleContent> =
    listOf(
        ComposeNativeHostBundleContent(
            files = project.files(runtimeFile).builtBy(runtimeBuiltBy),
            into = "Contents/Resources/native",
            executable = true,
            runtimes = setOf(ComposeNativeHostTargetRuntime.SharedLibrary),
        ),
        ComposeNativeHostBundleContent(
            files = project.files(nativeLibrariesDir).builtBy(nativeLibrariesBuiltBy),
            into = "Contents/Resources/native",
            executable = true,
            runtimes = setOf(ComposeNativeHostTargetRuntime.SharedLibrary),
        ),
    )

private fun configureJvmBundleTasks(
    project: Project,
    bundleConfig: NativeBundleConfig,
    buildNativeLauncher: TaskProvider<Exec>,
    buildNativeBridge: TaskProvider<Exec>,
    preparePatchedComposeDesktopRuntimeClasspath: TaskProvider<Task>,
    stageNativeAppBundle: TaskProvider<Sync>,
) {
    configureStageNativeAppBundle(
        project = project,
        bundleConfig = bundleConfig,
        taskProvider = stageNativeAppBundle,
        buildNativeLauncher = buildNativeLauncher,
        buildNativeBridge = buildNativeBridge,
        preparePatchedComposeDesktopRuntimeClasspath = preparePatchedComposeDesktopRuntimeClasspath,
        runtimeMode = ComposeNativeHostTargetRuntime.Jvm,
    )
    registerNativeDistributableTasks(
        project = project,
        bundleConfig = bundleConfig,
        buildNativeLauncher = buildNativeLauncher,
        buildNativeBridge = buildNativeBridge,
        preparePatchedComposeDesktopRuntimeClasspath = preparePatchedComposeDesktopRuntimeClasspath,
        distributableSpecs = jvmDistributableSpecs,
        runtimeMode = ComposeNativeHostTargetRuntime.Jvm,
    )
}

private fun configureNativeImageBundleTasks(
    project: Project,
    extension: ComposeNativeHostPluginExtension,
    bundleConfig: NativeBundleConfig,
    buildNativeLauncher: TaskProvider<Exec>,
    buildNativeBridge: TaskProvider<Exec>,
    preparePatchedComposeDesktopRuntimeClasspath: TaskProvider<Task>,
    additionalBundleContents: NativeImageBundleContents,
) {
    if (!extension.nativeImage.isConfigured()) {
        return
    }
    val stageNativeImageAppBundle = registerStageNativeImageAppBundleTask(project)
    registerNativeImageRunTask(project, extension, stageNativeImageAppBundle)
    registerNativeImageBundleInfoTask(project, extension, stageNativeImageAppBundle)
    configureStageNativeAppBundle(
        project = project,
        bundleConfig = bundleConfig,
        taskProvider = stageNativeImageAppBundle,
        buildNativeLauncher = buildNativeLauncher,
        buildNativeBridge = buildNativeBridge,
        preparePatchedComposeDesktopRuntimeClasspath = preparePatchedComposeDesktopRuntimeClasspath,
        runtimeMode = ComposeNativeHostTargetRuntime.SharedLibrary,
        additionalBundleContents = additionalBundleContents.dev,
    )
    registerNativeDistributableTasks(
        project = project,
        bundleConfig = bundleConfig,
        buildNativeLauncher = buildNativeLauncher,
        buildNativeBridge = buildNativeBridge,
        preparePatchedComposeDesktopRuntimeClasspath = preparePatchedComposeDesktopRuntimeClasspath,
        distributableSpecs = nativeImageDistributableSpecs,
        runtimeMode = ComposeNativeHostTargetRuntime.SharedLibrary,
        additionalBundleContents = additionalBundleContents.bundle,
    )
}

private data class NativeImageBundleContents(
    val dev: List<ComposeNativeHostBundleContent> = emptyList(),
    val bundle: List<ComposeNativeHostBundleContent> = emptyList(),
)

private fun registerNativeDistributableTasks(
    project: Project,
    bundleConfig: NativeBundleConfig,
    buildNativeLauncher: TaskProvider<Exec>,
    buildNativeBridge: TaskProvider<Exec>,
    preparePatchedComposeDesktopRuntimeClasspath: TaskProvider<Task>,
    distributableSpecs: List<NativeDistributableSpec>,
    runtimeMode: ComposeNativeHostTargetRuntime,
    additionalBundleContents: List<ComposeNativeHostBundleContent> = emptyList(),
) {
    distributableSpecs.forEach { spec ->
        val patchTask = project.tasks.named(spec.patchTaskName)
        configureComposeDesktopNativeDistributablePatchTask(
            taskProvider = patchTask,
            project = project,
            bundleConfig = bundleConfig,
            buildNativeLauncher = buildNativeLauncher,
            buildNativeBridge = buildNativeBridge,
            preparePatchedComposeDesktopRuntimeClasspath = preparePatchedComposeDesktopRuntimeClasspath,
            composeTaskName = spec.createTaskName,
            runtimeMode = runtimeMode,
            additionalBundleContents = additionalBundleContents,
        )
        project.tasks.named(spec.wrapperTaskName).configure {
            dependsOn(patchTask)
            spec.composeTaskName.takeIf { it != spec.createTaskName }?.let { composeTaskName ->
                dependsOn(project.tasks.matching { it.name == composeTaskName })
            }
        }
        spec.composeTaskName.takeIf { it != spec.createTaskName }?.let { composeTaskName ->
            project.tasks.matching { it.name == composeTaskName }.configureEach {
                mustRunAfter(patchTask)
                try {
                    val appImageProp = this.javaClass.getMethod("getAppImage").invoke(this) as org.gradle.api.file.DirectoryProperty
                    appImageProp.set(project.layout.dir(project.provider {
                        val isNativeImage = project.gradle.taskGraph.allTasks.any { it.name.contains("native-image", ignoreCase = true) }
                        val isRelease = project.gradle.taskGraph.allTasks.any { it.name.contains("Release", ignoreCase = true) }
                        val buildFolder = if (isRelease) "main-release" else "main"
                        val appDir = project.layout.buildDirectory.dir("compose/binaries/$buildFolder/app").get().asFile
                        val appBundles = appDir.listFiles()
                            ?.filter { it.isDirectory && (if (hostOsPrefix == "windows") !it.name.startsWith(".") else it.name.endsWith(".app")) }
                            .orEmpty()
                        if (appBundles.size == 1) {
                            appBundles.single()
                        } else {
                            val guessedName = spec.wrapperTaskName
                                .removePrefix("windows-native-image")
                                .removePrefix("windows")
                                .removePrefix("PackageRelease")
                                .removePrefix("Package")
                                .substringBefore("MSI")
                                .substringBefore("DMG")
                            File(appDir, guessedName)
                        }
                    }))
                } catch (e: Exception) {
                    // Ignore if task doesn't support appImage (reflection fallback)
                }
            }
        }
    }
}

private fun registerNativeDistributableTaskSkeletons(
    project: Project,
    distributableSpecs: List<NativeDistributableSpec>,
) {
    distributableSpecs.forEach { spec ->
        if (!project.tasks.names.contains(spec.patchTaskName)) {
            project.tasks.register(spec.patchTaskName, Task::class.java) {
                group = "distribution"
                description = "Applies the Compose Native Host bundle patch to ${spec.createTaskName}."
            }
        }
        if (!project.tasks.names.contains(spec.wrapperTaskName)) {
            project.tasks.register(spec.wrapperTaskName, Task::class.java) {
                group = "distribution"
                description = spec.description
            }
        }
    }
}

private data class NativeDistributableSpec(
    val createTaskName: String,
    val patchTaskName: String,
    val composeTaskName: String,
    val wrapperTaskName: String,
    val description: String,
)

private fun buildDistributableSpecs(runtime: ComposeNativeHostTargetRuntime): List<NativeDistributableSpec> {
    val prefix = if (runtime == ComposeNativeHostTargetRuntime.Jvm) hostOsPrefix else "$hostOsPrefix-native-image"
    val isWindows = hostOsPrefix == "windows"
    val packageTask = if (isWindows) "packageMsi" else "packageDmg"
    val packageReleaseTask = if (isWindows) "packageReleaseMsi" else "packageReleaseDmg"
    val packageDesc = if (isWindows) "MSI" else "DMG"

    return listOf(
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "${prefix}PatchDistributable",
            composeTaskName = "createDistributable",
            wrapperTaskName = "${prefix}CreateDistributable",
            description = "Builds the Compose Desktop distributable and applies the Compose Native Host patch.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "${prefix}PatchReleaseDistributable",
            composeTaskName = "createReleaseDistributable",
            wrapperTaskName = "${prefix}CreateReleaseDistributable",
            description = "Builds the release distributable and applies the Compose Native Host patch.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "${prefix}PatchDistributable",
            composeTaskName = packageTask,
            wrapperTaskName = "${prefix}Package$packageDesc",
            description = "Packages a $packageDesc from the native-host-patched Compose Desktop distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "${prefix}PatchReleaseDistributable",
            composeTaskName = packageReleaseTask,
            wrapperTaskName = "${prefix}PackageRelease$packageDesc",
            description = "Packages a release $packageDesc from the native-host-patched Compose Desktop distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "${prefix}PatchDistributable",
            composeTaskName = "packageDistributionForCurrentOS",
            wrapperTaskName = "${prefix}PackageDistributionForCurrentOS",
            description = "Packages the current OS distributable from the native-host-patched Compose Desktop app.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "${prefix}PatchReleaseDistributable",
            composeTaskName = "packageReleaseDistributionForCurrentOS",
            wrapperTaskName = "${prefix}PackageReleaseDistributionForCurrentOS",
            description = "Packages the current OS release distributable from the native-host-patched Compose Desktop app.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "${prefix}PatchDistributable",
            composeTaskName = "runDistributable",
            wrapperTaskName = "${prefix}RunDistributable",
            description = "Runs the native-host-patched Compose Desktop distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "${prefix}PatchReleaseDistributable",
            composeTaskName = "runReleaseDistributable",
            wrapperTaskName = "${prefix}RunReleaseDistributable",
            description = "Runs the native-host-patched release distributable.",
        ),
    )
}

private val jvmDistributableSpecs: List<NativeDistributableSpec>
    get() = buildDistributableSpecs(ComposeNativeHostTargetRuntime.Jvm)

private val nativeImageDistributableSpecs: List<NativeDistributableSpec>
    get() = buildDistributableSpecs(ComposeNativeHostTargetRuntime.SharedLibrary)
