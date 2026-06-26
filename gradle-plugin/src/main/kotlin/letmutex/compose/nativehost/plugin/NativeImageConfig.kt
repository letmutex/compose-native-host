package letmutex.compose.nativehost.plugin

import java.io.File
import java.util.Locale
import org.gradle.api.GradleException

data class NativeImageExperimentConfig(
    val helperMainClass: String,
    val mainClasses: List<String>,
    val helperSourcesDir: File,
    val helperResourcesDir: File,
    val generatedHelperSourcesDir: File,
    val helperJarFile: File,
    val composeUberJar: org.gradle.api.provider.Provider<File>,
    val filteredComposeUberJarFile: File,
    val sharedLibraryBaseName: String,
    val generatedJniConfigFile: File,
    val generatedJniConfigPackages: List<String>,
    val generatedJniConfigTypes: List<String>,
    val includeResourcePatterns: List<String>,
    val collectedSharedLibraryPatterns: List<String>,
    val preservePackages: List<String>,
    val devNativeLibrariesDir: File,
    val devSharedLibraryFile: File,
    val devSharedLibraryOutputDir: File,
    val devExtraBuildArgs: List<String>,
    val bundleNativeLibrariesDir: File,
    val bundleSharedLibraryFile: File,
    val bundleSharedLibraryOutputDir: File,
    val bundleExtraBuildArgs: List<String>,
)

internal const val sharedLibraryBindMainSymbol = "composeNativeHostRuntimeBindMain"

internal enum class NativeImageSharedLibraryFlavor(
    val taskSuffix: String,
    val description: String,
) {
    Dev(
        taskSuffix = "NativeImageBuildSharedLibrary",
        description = "Builds the hosted app shared library for staged native-image runs.",
    ),
    Bundle(
        taskSuffix = "NativeImageBuildBundleSharedLibrary",
        description = "Builds the hosted app shared library for patched distributables.",
    );

    val taskName: String
        get() = "$hostOsPrefix$taskSuffix"
}

internal data class NativeImageHostResourceFilter(
    val hostLibrarySuffixes: Set<String>,
    val hostOsTokens: Set<String>,
    val hostArchTokens: Set<String>,
) {
    fun keep(entryName: String): Boolean {
        if (!isNativeArtifact(entryName)) {
            return true
        }
        val normalizedEntryName = entryName.lowercase(Locale.US)
        if (!hostLibrarySuffixes.any { suffix ->
                normalizedEntryName.endsWith(suffix) || normalizedEntryName.endsWith("$suffix.sha256")
            }
        ) {
            return false
        }
        val osTokens = matchingTokens(normalizedEntryName, knownOsTokens)
        if (osTokens.isNotEmpty() && osTokens.none(hostOsTokens::contains)) {
            return false
        }
        val archTokens = matchingTokens(normalizedEntryName, knownArchTokens)
        if (archTokens.isNotEmpty() && archTokens.none(hostArchTokens::contains)) {
            return false
        }
        return true
    }
}

private fun isNativeArtifact(entryName: String): Boolean {
    val normalizedEntryName = entryName.lowercase(Locale.US)
    return nativeFileSuffixes.any(normalizedEntryName::endsWith) ||
        nativeChecksumSuffixes.any(normalizedEntryName::endsWith)
}

private fun matchingTokens(
    entryName: String,
    knownTokens: Set<String>,
): Set<String> =
    knownTokens.filterTo(linkedSetOf()) { token ->
        Regex("(^|[./_\\-])${Regex.escape(token)}($|[./_\\-])").containsMatchIn(entryName)
    }

private val nativeFileSuffixes = setOf(".dylib", ".jnilib", ".so", ".dll")
private val nativeChecksumSuffixes = nativeFileSuffixes.mapTo(linkedSetOf()) { "$it.sha256" }
private val knownOsTokens = setOf("linux", "windows", "mac", "macos", "osx", "darwin", "freebsd")
private val knownArchTokens = setOf("aarch64", "arm64", "armv6", "armv7", "arm", "amd64", "x64", "x86_64", "x86", "ppc64")

internal fun sharedLibraryFile(
    config: NativeImageExperimentConfig,
    flavor: NativeImageSharedLibraryFlavor,
): File =
    when (flavor) {
        NativeImageSharedLibraryFlavor.Dev -> config.devSharedLibraryFile
        NativeImageSharedLibraryFlavor.Bundle -> config.bundleSharedLibraryFile
    }

internal fun sharedLibraryOutputDir(
    config: NativeImageExperimentConfig,
    flavor: NativeImageSharedLibraryFlavor,
): File =
    when (flavor) {
        NativeImageSharedLibraryFlavor.Dev -> config.devSharedLibraryOutputDir
        NativeImageSharedLibraryFlavor.Bundle -> config.bundleSharedLibraryOutputDir
    }

internal fun sharedLibraryExtraBuildArgs(
    config: NativeImageExperimentConfig,
    flavor: NativeImageSharedLibraryFlavor,
): List<String> =
    when (flavor) {
        NativeImageSharedLibraryFlavor.Dev -> config.devExtraBuildArgs
        NativeImageSharedLibraryFlavor.Bundle -> config.bundleExtraBuildArgs
    }
