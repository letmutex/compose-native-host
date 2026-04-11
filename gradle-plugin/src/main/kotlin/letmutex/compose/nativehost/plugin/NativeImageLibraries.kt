package letmutex.compose.nativehost.plugin

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

private val hostSharedLibrarySuffixes = setOf(".dylib", ".jnilib", ".so", ".dll")

internal fun registerNativeImageExtractHostLibrariesTask(
    project: Project,
    config: NativeImageExperimentConfig,
    filterComposeUberJar: TaskProvider<Task>,
    flavor: NativeImageSharedLibraryFlavor,
): TaskProvider<Task> =
    project.tasks.register(
        when (flavor) {
            NativeImageSharedLibraryFlavor.Dev -> "macosNativeImageExtractDevLibraries"
            NativeImageSharedLibraryFlavor.Bundle -> "macosNativeImageExtractBundleLibraries"
        },
    ) {
        group = "build"
        description =
            when (flavor) {
                NativeImageSharedLibraryFlavor.Dev ->
                    "Extracts host shared libraries for staged native-image runs."
                NativeImageSharedLibraryFlavor.Bundle ->
                    "Extracts host shared libraries for distributable native-image bundles."
            }
        dependsOn(filterComposeUberJar)
        inputs.file(config.filteredComposeUberJarFile)
        inputs.property(
            "composeNativeHostNativeImageCollectedSharedLibraryPatterns",
            config.collectedSharedLibraryPatterns,
        )
        outputs.dir(nativeLibrariesDir(config, flavor))

        doLast {
            extractNativeLibrariesFromJar(
                sourceJar = config.filteredComposeUberJarFile,
                outputDir = nativeLibrariesDir(config, flavor),
                includePatterns = config.collectedSharedLibraryPatterns,
            )
        }
    }

internal fun nativeLibrariesDir(
    config: NativeImageExperimentConfig,
    flavor: NativeImageSharedLibraryFlavor,
): File =
    when (flavor) {
        NativeImageSharedLibraryFlavor.Dev -> config.devNativeLibrariesDir
        NativeImageSharedLibraryFlavor.Bundle -> config.bundleNativeLibrariesDir
    }

private fun extractNativeLibrariesFromJar(
    sourceJar: File,
    outputDir: File,
    includePatterns: List<String>,
) {
    outputDir.deleteRecursively()
    outputDir.mkdirs()
    if (includePatterns.isEmpty()) {
        return
    }
    val includeRegexes = includePatterns.map(::Regex)
    val extractedNames = linkedSetOf<String>()
    ZipFile(sourceJar).use { input ->
        val entries = input.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !shouldCollectSharedLibrary(entry.name, includeRegexes)) {
                continue
            }
            val outputName = entry.name.substringAfterLast('/')
            check(extractedNames.add(outputName)) {
                "Duplicate native library name '$outputName' found while extracting ${sourceJar.absolutePath}."
            }
            val outputFile = File(outputDir, outputName)
            input.getInputStream(entry).use { entryStream ->
                outputFile.outputStream().use { outputStream ->
                    entryStream.copyTo(outputStream)
                }
            }
        }
    }
}

private fun isHostSharedLibrary(entryName: String): Boolean {
    val normalizedEntryName = entryName.lowercase()
    return hostSharedLibrarySuffixes.any(normalizedEntryName::endsWith)
}

private fun shouldCollectSharedLibrary(
    entryName: String,
    includeRegexes: List<Regex>,
): Boolean =
    isHostSharedLibrary(entryName) && includeRegexes.any { regex ->
        regex.containsMatchIn(entryName)
    }
