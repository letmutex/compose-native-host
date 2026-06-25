package letmutex.compose.nativehost.plugin

import java.io.File
import org.gradle.api.Project
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

internal fun bundlesJvmRuntime(runtimeMode: ComposeNativeHostTargetRuntime): Boolean =
    runtimeMode == ComposeNativeHostTargetRuntime.Jvm

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
        extraBundleContents = if (hostOsPrefix == "windows") extension.windows.bundleContents() else extension.macos.bundleContents(),
        jvmArgs = extension.jvmArgs.getOrElse(emptyList()),
        mainClass = mainClass,
    )
}
