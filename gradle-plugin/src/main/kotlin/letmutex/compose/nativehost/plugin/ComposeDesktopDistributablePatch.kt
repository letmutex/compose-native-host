package letmutex.compose.nativehost.plugin

import java.io.File
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.Project

internal fun patchComposeDesktopBundle(
    project: Project,
    bundleConfig: NativeBundleConfig,
    launcherFile: File,
    composeTaskName: String,
    runtimeMode: ComposeNativeHostTargetRuntime,
    additionalBundleContents: List<ComposeNativeHostBundleContent> = emptyList(),
) {
    val appDir =
        project.layout.buildDirectory
            .dir("compose/binaries/${composeDistributableBuildFolder(composeTaskName)}/app")
            .get()
            .asFile
    val appBundle = resolveComposeDistributableBundle(appDir) ?: return

    val packagedExecutableName = resolvePackagedExecutableName(appBundle)
    val executableFile = File(appBundle, "Contents/MacOS/$packagedExecutableName")
    launcherFile.copyTo(executableFile, overwrite = true)
    executableFile.setExecutable(true)

    val bundledBridgeFile = File(appBundle, "Contents/Resources/native/${bundleConfig.nativeBridgeFile.name}")
    bundledBridgeFile.parentFile.mkdirs()
    bundleConfig.nativeBridgeFile.copyTo(bundledBridgeFile, overwrite = true)
    bundledBridgeFile.setExecutable(true)

    copyDirectoryContents(
        sourceDir = bundleConfig.appResourcesDir,
        targetDir = File(appBundle, "Contents/Resources"),
    )
    if (runtimeMode == ComposeNativeHostTargetRuntime.Jvm) {
        replaceComposeDesktopRuntimeJars(
            bundleConfig = bundleConfig,
            appBundle = appBundle,
        )
    } else {
        removePackagedJvmRuntime(appBundle)
    }
    copyExtraBundleContents(
        bundleConfig = bundleConfig,
        appBundle = appBundle,
        runtimeMode = runtimeMode,
        additionalBundleContents = additionalBundleContents,
    )
    patchRuntimeModeInfoPlist(appBundle, runtimeMode)
}

private fun composeDistributableBuildFolder(taskName: String): String =
    if (taskName.contains("Release", ignoreCase = true)) {
        "main-release"
    } else {
        "main"
    }

private fun resolveComposeDistributableBundle(
    appDir: File,
): File? {
    val appBundles =
        appDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".app") }
            .orEmpty()
    return when (appBundles.size) {
        0 -> null
        1 -> appBundles.single()
        else -> throw GradleException("Expected one .app bundle in ${appDir.absolutePath}, found ${appBundles.size}.")
    }
}

private fun resolvePackagedExecutableName(appBundle: File): String {
    val infoPlist = File(appBundle, "Contents/Info.plist")
    val contents = infoPlist.readText()
    val match =
        Regex(
            pattern = "<key>CFBundleExecutable</key>\\s*<string>([^<]+)</string>",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(contents)
            ?: throw GradleException("Could not resolve CFBundleExecutable from ${infoPlist.absolutePath}.")
    return xmlUnescape(match.groupValues[1].trim())
}

private fun replaceComposeDesktopRuntimeJars(
    bundleConfig: NativeBundleConfig,
    appBundle: File,
) {
    val packagedAppDir = File(appBundle, "Contents/app")
    val stagedArtifacts = readStagedRuntimeArtifacts(bundleConfig.patchedRuntimeClasspathMapFile)
    val packagedJarsByOriginalName = resolvePackagedRuntimeJarsByOriginalName(packagedAppDir, stagedArtifacts)
    stagedArtifacts.forEach { artifact ->
        val stagedJar = File(bundleConfig.patchedRuntimeClasspathDir, artifact.stagedJarName)
        if (!stagedJar.exists()) {
            throw GradleException("Missing staged runtime jar: ${stagedJar.absolutePath}")
        }
        val packagedJar =
            packagedJarsByOriginalName[artifact]
                ?: throw GradleException(
                    "Could not resolve packaged runtime jar mapping for ${artifact.originalJarName} in ${packagedAppDir.absolutePath}.",
                )
        stagedJar.copyTo(packagedJar, overwrite = true)
    }
}

private fun readStagedRuntimeArtifacts(mapFile: File): List<StagedRuntimeArtifact> =
    mapFile.readLines()
        .filter(String::isNotBlank)
        .map { line ->
            val separator = line.indexOf('|')
            check(separator >= 0) {
                "Invalid runtime jar map entry: $line"
            }
            val secondSeparator = line.indexOf('|', separator + 1)
            check(secondSeparator >= 0) {
                "Invalid runtime jar map entry: $line"
            }
            val thirdSeparator = line.indexOf('|', secondSeparator + 1)
            check(thirdSeparator >= 0) {
                "Invalid runtime jar map entry: $line"
            }
            StagedRuntimeArtifact(
                stagedJarName = line.substring(0, separator),
                originalJarName = line.substring(separator + 1, secondSeparator),
                packagedJarName = line.substring(secondSeparator + 1, thirdSeparator),
                originalJarMd5 = line.substring(thirdSeparator + 1),
            )
        }

private fun resolvePackagedRuntimeJarsByOriginalName(
    packagedAppDir: File,
    stagedArtifacts: List<StagedRuntimeArtifact>,
): Map<StagedRuntimeArtifact, File> {
    val packagedClasspathJarNames = readPackagedClasspathJarNames(packagedAppDir)
    val resolved = linkedMapOf<StagedRuntimeArtifact, File>()
    val packagedJarsByName =
        packagedClasspathJarNames
            .map(::File)
            .associateBy { it.name }

    stagedArtifacts.forEach { artifact ->
        val packagedJar =
            packagedJarsByName[artifact.packagedJarName]
                ?: resolvePackagedRuntimeJarByBaseName(packagedAppDir, packagedJarsByName.values, artifact)
        resolved[artifact] = packagedJar
    }
    return resolved
}

private fun resolvePackagedRuntimeJarByBaseName(
    packagedAppDir: File,
    packagedJars: Collection<File>,
    artifact: StagedRuntimeArtifact,
): File {
    val originalBaseName = artifact.originalJarName.removeSuffix(".jar")
    val matches =
        packagedJars.filter { file ->
            // Compose Desktop rewrites jar file names with a trailing hash. Match against the
            // pre-hash stem only as a fallback when the persisted hashed name is unavailable.
            file.parentFile == packagedAppDir &&
                packagedJarOriginalBaseName(file) == originalBaseName
        }
    val md5Matches = matches.filter { it.md5Hex() == artifact.originalJarMd5 }
    return when {
        matches.isEmpty() -> throw GradleException(
            "Could not find packaged runtime jar for ${artifact.originalJarName} in ${packagedAppDir.absolutePath}.",
        )
        md5Matches.size == 1 -> md5Matches.single()
        matches.size == 1 -> matches.single()
        else -> throw GradleException(
            "Found multiple packaged runtime jars for ${artifact.originalJarName} in ${packagedAppDir.absolutePath}.",
        )
    }
}

private fun File.md5Hex(): String =
    MessageDigest.getInstance("MD5").digest(readBytes()).joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

private fun packagedJarOriginalBaseName(file: File): String {
    val stem = file.nameWithoutExtension
    val separatorIndex = stem.lastIndexOf('-')
    if (separatorIndex <= 0) {
        return stem
    }
    val suffix = stem.substring(separatorIndex + 1)
    // Treat the last dash-separated token as Compose Desktop's hash only when it actually looks hash-like.
    return if (suffix.matches(Regex("[0-9a-f]{8,}"))) {
        stem.substring(0, separatorIndex)
    } else {
        stem
    }
}

private fun readPackagedClasspathJarNames(
    packagedAppDir: File,
): List<String> {
    val cfgFiles =
        packagedAppDir.listFiles()
            ?.filter { it.isFile && it.extension == "cfg" }
            .orEmpty()
    val cfgFile =
        when (cfgFiles.size) {
            1 -> cfgFiles.single()
            0 -> throw GradleException("Could not find packaged .cfg file in ${packagedAppDir.absolutePath}.")
            else -> throw GradleException("Expected one packaged .cfg file in ${packagedAppDir.absolutePath}, found ${cfgFiles.size}.")
        }
    // The generated .cfg is the source of truth for the packaged classpath order and final jar names.
    return cfgFile.readLines()
        .mapNotNull { line ->
            val value = line.substringAfter("app.classpath=", missingDelimiterValue = "")
            if (value.isBlank()) {
                null
            } else {
                val jarName = value.substringAfterLast('/')
                if (jarName.endsWith(".jar")) {
                    File(packagedAppDir, jarName).absolutePath
                } else {
                    null
                }
            }
        }
}

private fun copyExtraBundleContents(
    bundleConfig: NativeBundleConfig,
    appBundle: File,
    runtimeMode: ComposeNativeHostTargetRuntime,
    additionalBundleContents: List<ComposeNativeHostBundleContent>,
) {
    (bundleConfig.extraBundleContents + additionalBundleContents)
        .filter { content -> content.supports(runtimeMode) }
        .forEach { content ->
        val targetDir = File(appBundle, content.into)
        content.files.files.forEach { source ->
            if (!source.exists()) {
                return@forEach
            }
            copyBundleContent(
                source = source,
                targetDir = targetDir,
                executable = content.executable,
            )
        }
    }
}

private fun patchRuntimeModeInfoPlist(
    appBundle: File,
    runtimeMode: ComposeNativeHostTargetRuntime,
) {
    val infoPlist = File(appBundle, "Contents/Info.plist")
    val contents = infoPlist.readText()
    val patchedContents =
        if (contents.contains("<key>ComposeNativeHostRuntimeMode</key>")) {
            contents.replace(
                Regex(
                    pattern = "<key>ComposeNativeHostRuntimeMode</key>\\s*<string>[^<]+</string>",
                    options = setOf(RegexOption.DOT_MATCHES_ALL),
                ),
                "<key>ComposeNativeHostRuntimeMode</key>\n    <string>${runtimeMode.plistValue}</string>",
            )
        } else {
            val rootDictEnd = contents.lastIndexOf("</dict>")
            check(rootDictEnd >= 0) {
                "Could not locate root dict in ${infoPlist.absolutePath}."
            }
            contents.replaceRange(
                rootDictEnd,
                rootDictEnd + "</dict>".length,
                "  <key>ComposeNativeHostRuntimeMode</key>\n  <string>${runtimeMode.plistValue}</string>\n</dict>",
            )
        }
    infoPlist.writeText(patchedContents)
}

private fun removePackagedJvmRuntime(appBundle: File) {
    val packagedAppDir = File(appBundle, "Contents/app")
    packagedAppDir.listFiles()?.forEach { child ->
        if (shouldKeepPackagedAppEntry(child)) {
            return@forEach
        }
        child.deleteRecursively()
    }
    File(appBundle, "Contents/runtime").deleteRecursively()
}

private fun shouldKeepPackagedAppEntry(file: File): Boolean {
    if (file.isDirectory) {
        return file.name == "resources"
    }
    return when {
        file.name == ".jpackage.xml" -> true
        file.name.endsWith(".cfg") -> true
        else -> false
    }
}

private fun copyBundleContent(
    source: File,
    targetDir: File,
    executable: Boolean,
) {
    if (source.isDirectory) {
        copyDirectoryContents(source, targetDir, executable)
        return
    }
    targetDir.mkdirs()
    val targetFile = File(targetDir, source.name)
    source.copyTo(targetFile, overwrite = true)
    if (executable) {
        targetFile.setExecutable(true)
    }
}

private fun copyDirectoryContents(
    sourceDir: File,
    targetDir: File,
    executable: Boolean = false,
) {
    if (!sourceDir.exists()) {
        return
    }
    sourceDir.walkTopDown()
        .filter { it != sourceDir }
        .forEach { source ->
            val relativePath = source.relativeTo(sourceDir)
            val target = File(targetDir, relativePath.path)
            if (source.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile.mkdirs()
                source.copyTo(target, overwrite = true)
                if (executable) {
                    target.setExecutable(true)
                }
            }
        }
}

private fun xmlUnescape(value: String): String =
    value
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
