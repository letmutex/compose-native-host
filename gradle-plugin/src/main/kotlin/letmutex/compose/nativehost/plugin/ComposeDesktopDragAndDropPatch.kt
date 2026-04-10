package letmutex.compose.nativehost.plugin

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

private val patchedRuntimeClassEntryPrefixes =
    listOf(
        "androidx/compose/ui/",
        "androidx/lifecycle/",
        "org/jetbrains/skiko/",
    )

private val patchedRuntimeOwnerArtifacts =
    setOf(
        "org.jetbrains.compose.ui:ui-desktop",
        "androidx.lifecycle:lifecycle-runtime-desktop",
        "org.jetbrains.androidx.lifecycle:lifecycle-runtime-desktop",
        "org.jetbrains.skiko:skiko-awt",
    )

private const val nativeHostRuntimeMarkerEntry = "letmutex/compose/nativehost/ComposeRuntime.class"

fun registerPreparePatchedComposeDesktopRuntimeClasspathTask(
    project: Project,
    bundleConfig: NativeBundleConfig,
): TaskProvider<Task> {
    val runtimeClasspath = project.configurations.named("${bundleConfig.jvmTargetName}RuntimeClasspath")
    return project.tasks.register("preparePatchedComposeDesktopRuntimeClasspath") {
        group = "build"
        description = "Prepares runtime jars with Compose Desktop native-host overrides."
        inputs.files(runtimeClasspath)
        outputs.dir(bundleConfig.patchedRuntimeClasspathDir)
        outputs.file(bundleConfig.patchedRuntimeClasspathMapFile)

        doLast {
            val outputDir = bundleConfig.patchedRuntimeClasspathDir
            val mapFile = bundleConfig.patchedRuntimeClasspathMapFile
            outputDir.deleteRecursively()
            outputDir.mkdirs()
            val runtimeArtifacts =
                runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
                    .filter { it.isFile() && it.file.extension == "jar" }
                    .distinctBy { it.file.absolutePath }
            val runtimeJar = resolveNativeHostRuntimeJar(runtimeArtifacts)
            val patchedRuntimeEntries = collectPatchedRuntimeClassEntries(runtimeJar)
            val stagedArtifacts =
                runtimeArtifacts.map { artifact ->
                    val inputJar = artifact.file
                    val outputJar = File(outputDir, artifact.stagedJarName())
                    if (
                        patchedRuntimeEntries.isNotEmpty() &&
                        shouldStripPatchedRuntimeEntries(
                            artifact = artifact,
                            runtimeJar = runtimeJar,
                            patchedRuntimeEntries = patchedRuntimeEntries,
                        )
                    ) {
                        copyJarRemovingEntries(inputJar, outputJar, patchedRuntimeEntries)
                    } else {
                        inputJar.copyTo(outputJar, overwrite = true)
                    }
                    StagedRuntimeArtifact(
                        originalJarName = inputJar.name,
                        stagedJarName = outputJar.name,
                        packagedJarName = inputJar.packagedJarName(),
                        originalJarMd5 = inputJar.md5Hex(),
                    )
                }
            mapFile.parentFile.mkdirs()
            // Persist the original jar identity next to the staged output so distributable patching can
            // replace Compose Desktop's packaged jars without guessing its hashed file names.
            mapFile.writeText(
                stagedArtifacts.joinToString(separator = "\n") { artifact ->
                    "${artifact.stagedJarName}|${artifact.originalJarName}|${artifact.packagedJarName}|${artifact.originalJarMd5}"
                } + "\n",
            )
        }
    }
}

data class StagedRuntimeArtifact(
    val originalJarName: String,
    val stagedJarName: String,
    val packagedJarName: String,
    val originalJarMd5: String,
)

private fun ResolvedArtifact.isFile(): Boolean = file.isFile

private fun ResolvedArtifact.stagedJarName(): String {
    val group = moduleVersion.id.group.sanitizeJarComponent().ifEmpty { "local" }
    val name = name.sanitizeJarComponent().ifEmpty { file.nameWithoutExtension.sanitizeJarComponent() }
    val version = moduleVersion.id.version.sanitizeJarComponent().ifEmpty { "unspecified" }
    return "$group-$name-$version.jar"
}

private fun String.sanitizeJarComponent(): String =
    buildString(length) {
        for (char in this@sanitizeJarComponent) {
            append(if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '-')
        }
    }

private fun resolveNativeHostRuntimeJar(
    artifacts: List<ResolvedArtifact>,
): File? =
    artifacts
        .asSequence()
        .map { it.file }
        .firstOrNull { jarContainsEntry(it, nativeHostRuntimeMarkerEntry) }

private fun collectPatchedRuntimeClassEntries(
    runtimeJar: File?,
): Set<String> {
    runtimeJar ?: return emptySet()
    return ZipFile(runtimeJar).use { zip ->
        zip.entries().asSequence()
            .map(ZipEntry::getName)
            .filter { entryName ->
                entryName.endsWith(".class") &&
                    patchedRuntimeClassEntryPrefixes.any(entryName::startsWith)
            }
            .toCollection(linkedSetOf())
    }
}

private fun shouldStripPatchedRuntimeEntries(
    artifact: ResolvedArtifact,
    runtimeJar: File?,
    patchedRuntimeEntries: Set<String>,
): Boolean =
    artifact.file != runtimeJar &&
        artifact.coordinateKey() in patchedRuntimeOwnerArtifacts &&
        jarContainsAnyEntry(artifact.file, patchedRuntimeEntries)

private fun ResolvedArtifact.coordinateKey(): String =
    "${moduleVersion.id.group}:$name"

private fun jarContainsAnyEntry(
    jarFile: File,
    entryNames: Set<String>,
): Boolean =
    ZipFile(jarFile).use { zip ->
        zip.entries().asSequence().any { it.name in entryNames }
    }

private fun jarContainsEntry(
    jarFile: File,
    entryName: String,
): Boolean =
    ZipFile(jarFile).use { zip ->
        zip.getEntry(entryName) != null
    }

private fun copyJarRemovingEntries(
    inputJar: File,
    outputJar: File,
    removedEntries: Collection<String>,
) {
    val removedEntrySet = removedEntries.toSet()
    ZipFile(inputJar).use { zip ->
        ZipOutputStream(outputJar.outputStream().buffered()).use { output ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.name in removedEntrySet) {
                    return@forEach
                }
                output.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                if (!entry.isDirectory) {
                    zip.getInputStream(entry).use { input ->
                        input.copyTo(output)
                    }
                }
                output.closeEntry()
            }
        }
    }
}

private fun File.packagedJarName(): String {
    val hash = MessageDigest.getInstance("MD5").digest(readBytes()).toComposeDesktopHashString()
    return "$nameWithoutExtension-$hash.jar"
}

private fun File.md5Hex(): String =
    MessageDigest.getInstance("MD5").digest(readBytes()).joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

private fun ByteArray.toComposeDesktopHashString(): String =
    joinToString(separator = "") { byte ->
        byte.toUByte().toString(16)
    }
