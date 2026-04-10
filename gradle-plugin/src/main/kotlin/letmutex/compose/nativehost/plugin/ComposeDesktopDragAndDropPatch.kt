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

private val strippedComposeDesktopDragAndDropEntries =
    listOf(
        "androidx/compose/ui/draganddrop/DragAndDropEvent.class",
        "androidx/compose/ui/draganddrop/DragAndDropTransferAction.class",
        "androidx/compose/ui/draganddrop/DragAndDropTransferAction\$Companion.class",
        "androidx/compose/ui/draganddrop/DragAndDropTransferData.class",
        "androidx/compose/ui/draganddrop/DragAndDropTransferable.class",
        "androidx/compose/ui/draganddrop/DragAndDrop_desktopKt.class",
        "androidx/compose/ui/draganddrop/DragAndDrop_desktopKt\$DragAndDropTransferable\$1.class",
    )

private val strippedComposeDesktopClipboardEntries =
    listOf(
        "androidx/compose/ui/platform/AwtPlatformClipboard.class",
        "androidx/compose/ui/platform/ClipEntry.class",
        "androidx/compose/ui/platform/ClipMetadata.class",
        "androidx/compose/ui/platform/EmptyTransferable.class",
        "androidx/compose/ui/platform/PlatformClipboard_desktopKt.class",
    )

private val strippedComposeDesktopOverrideEntries =
    strippedComposeDesktopDragAndDropEntries + strippedComposeDesktopClipboardEntries

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
            val stagedArtifacts =
                runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
                    .filter { it.isFile() && it.file.extension == "jar" }
                    .distinctBy { it.file.absolutePath }
                    .map { artifact ->
                        val inputJar = artifact.file
                        val outputJar = File(outputDir, artifact.stagedJarName())
                        if (inputJar.name.startsWith("ui-desktop-")) {
                            copyJarRemovingEntries(inputJar, outputJar, strippedComposeDesktopOverrideEntries)
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
