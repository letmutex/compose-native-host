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

        configureBuildNativeLauncher(
            project = project,
            config = launcherConfig,
            taskProvider = buildNativeLauncher,
            extractTask = extractTask,
            buildNativeBridge = buildNativeBridge,
            generateJvmArgsSwiftSource = generateJvmArgsSwiftSource,
        )
        configureBuildNativeBridge(
            project = project,
            config = launcherConfig,
            taskProvider = buildNativeBridge,
            extractTask = extractTask,
            generateJvmArgsSwiftSource = generateJvmArgsSwiftSource,
        )
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
                dependsOn(composeTaskName)
            }
        }
        spec.composeTaskName.takeIf { it != spec.createTaskName }?.let { composeTaskName ->
            project.tasks.matching { it.name == composeTaskName }.configureEach {
                mustRunAfter(patchTask)
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

private val jvmDistributableSpecs =
    listOf(
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosPatchDistributable",
            composeTaskName = "createDistributable",
            wrapperTaskName = "macosCreateDistributable",
            description = "Builds the Compose Desktop distributable and applies the Compose Native Host patch.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosPatchReleaseDistributable",
            composeTaskName = "createReleaseDistributable",
            wrapperTaskName = "macosCreateReleaseDistributable",
            description = "Builds the release distributable and applies the Compose Native Host patch.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosPatchDistributable",
            composeTaskName = "packageDmg",
            wrapperTaskName = "macosPackageDmg",
            description = "Packages a DMG from the native-host-patched Compose Desktop distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosPatchReleaseDistributable",
            composeTaskName = "packageReleaseDmg",
            wrapperTaskName = "macosPackageReleaseDmg",
            description = "Packages a release DMG from the native-host-patched Compose Desktop distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosPatchDistributable",
            composeTaskName = "packageDistributionForCurrentOS",
            wrapperTaskName = "macosPackageDistributionForCurrentOS",
            description = "Packages the current OS distributable from the native-host-patched Compose Desktop app.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosPatchReleaseDistributable",
            composeTaskName = "packageReleaseDistributionForCurrentOS",
            wrapperTaskName = "macosPackageReleaseDistributionForCurrentOS",
            description = "Packages the current OS release distributable from the native-host-patched Compose Desktop app.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosPatchDistributable",
            composeTaskName = "runDistributable",
            wrapperTaskName = "macosRunDistributable",
            description = "Runs the native-host-patched Compose Desktop distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosPatchReleaseDistributable",
            composeTaskName = "runReleaseDistributable",
            wrapperTaskName = "macosRunReleaseDistributable",
            description = "Runs the native-host-patched release distributable.",
        ),
    )

private val nativeImageDistributableSpecs =
    listOf(
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosNativeImagePatchDistributable",
            composeTaskName = "createDistributable",
            wrapperTaskName = "macosNativeImageCreateDistributable",
            description = "Builds the Compose Desktop distributable and applies the native-image Compose Native Host patch.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosNativeImagePatchReleaseDistributable",
            composeTaskName = "createReleaseDistributable",
            wrapperTaskName = "macosNativeImageCreateReleaseDistributable",
            description = "Builds the release distributable and applies the native-image Compose Native Host patch.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosNativeImagePatchDistributable",
            composeTaskName = "packageDmg",
            wrapperTaskName = "macosNativeImagePackageDmg",
            description = "Packages a DMG from the native-image Compose Native Host distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosNativeImagePatchReleaseDistributable",
            composeTaskName = "packageReleaseDmg",
            wrapperTaskName = "macosNativeImagePackageReleaseDmg",
            description = "Packages a release DMG from the native-image Compose Native Host distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosNativeImagePatchDistributable",
            composeTaskName = "packageDistributionForCurrentOS",
            wrapperTaskName = "macosNativeImagePackageDistributionForCurrentOS",
            description = "Packages the current OS native-image Compose Native Host distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosNativeImagePatchReleaseDistributable",
            composeTaskName = "packageReleaseDistributionForCurrentOS",
            wrapperTaskName = "macosNativeImagePackageReleaseDistributionForCurrentOS",
            description = "Packages the current OS release native-image Compose Native Host distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createDistributable",
            patchTaskName = "macosNativeImagePatchDistributable",
            composeTaskName = "runDistributable",
            wrapperTaskName = "macosNativeImageRunDistributable",
            description = "Runs the native-image Compose Native Host distributable.",
        ),
        NativeDistributableSpec(
            createTaskName = "createReleaseDistributable",
            patchTaskName = "macosNativeImagePatchReleaseDistributable",
            composeTaskName = "runReleaseDistributable",
            wrapperTaskName = "macosNativeImageRunReleaseDistributable",
            description = "Runs the release native-image Compose Native Host distributable.",
        ),
    )
