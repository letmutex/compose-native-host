package letmutex.compose.nativehost.plugin

import java.io.File
import java.io.InputStream
import java.io.PrintStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar

internal const val nativeBridgeModuleName = "ComposeNativeHost"
internal const val nativeBridgeLibraryName = "compose-native-host"
internal const val nativeHostJvmArgsTypeName = "ComposeNativeHostJvmArgs"

internal fun resolveMainClass(project: Project, extension: ComposeNativeHostPluginExtension): String {
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

internal fun swiftSources(directory: File): List<File> {
    if (!directory.exists()) {
        return emptyList()
    }
    return directory.listFiles()
        ?.filter { it.isFile && it.extension == "swift" }
        ?.sortedBy(File::getName)
        .orEmpty()
}

internal fun cppSources(directory: File): List<File> {
    if (!directory.exists()) {
        return emptyList()
    }
    return directory.listFiles()
        ?.filter { it.isFile && (it.extension == "cpp" || it.extension == "c" || it.extension == "cc") }
        ?.sortedBy(File::getName)
        .orEmpty()
}

internal fun shellQuote(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"

internal fun writeJvmArgsSwiftSource(
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

internal fun renderInfoPlist(
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

internal fun launchNativeBundle(
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

internal fun codesignBundle(
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

internal fun locateVcvars64(project: Project): File {
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

internal fun mapStagedPath(intoPath: String): String {
    if (hostOsPrefix != "windows") return intoPath
    return when {
        intoPath.startsWith("Contents/Resources/native") -> "native"
        intoPath.startsWith("Contents/Resources") -> "app/resources"
        intoPath.startsWith("Contents/app") -> "app"
        else -> intoPath
    }
}
