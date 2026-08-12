package letmutex.compose.nativehost.plugin

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class NativeImageHostResourceFilterTest {
    @Test
    fun `nativeImageHostResourceFilter supports all host resource paths`() {
        val hosts =
            buildList {
                addHosts(
                    osNames = listOf("Windows 10", "Windows 11", "Windows Server 2022"),
                    osArches = listOf("amd64", "x64", "x86-64", "x86_64"),
                    resourcePath = "win32-x86-64",
                    librarySuffix = ".dll",
                )
                addHosts(
                    osNames = listOf("Windows 10", "Windows 11", "Windows Server 2022"),
                    osArches = listOf("aarch64", "arm64"),
                    resourcePath = "win32-arm64",
                    librarySuffix = ".dll",
                )
                addHosts(
                    osNames = listOf("Linux", "LINUX"),
                    osArches = listOf("amd64", "x64", "x86-64", "x86_64"),
                    resourcePath = "linux-x86-64",
                    librarySuffix = ".so",
                )
                addHosts(
                    osNames = listOf("Linux", "LINUX"),
                    osArches = listOf("aarch64", "arm64"),
                    resourcePath = "linux-aarch64",
                    librarySuffix = ".so",
                )
                addHosts(
                    osNames = listOf("Mac OS X", "macOS", "Darwin"),
                    osArches = listOf("amd64", "x64", "x86-64", "x86_64"),
                    resourcePath = "darwin-x86-64",
                    librarySuffix = ".dylib",
                )
                addHosts(
                    osNames = listOf("Mac OS X", "macOS", "Darwin"),
                    osArches = listOf("aarch64", "arm64"),
                    resourcePath = "darwin-aarch64",
                    librarySuffix = ".dylib",
                )
            }

        hosts.forEach { host ->
            val filter = nativeImageHostResourceFilter(host.osName, host.osArch)
            val matchingEntry = "native/${host.resourcePath}/library${host.librarySuffix}"
            assertTrue(filter.keep(matchingEntry), "Expected $matchingEntry to be kept")

            hosts.filterNot { it.resourcePath == host.resourcePath }.forEach { other ->
                val otherEntry = "native/${other.resourcePath}/library${other.librarySuffix}"
                assertFalse(filter.keep(otherEntry), "Expected $otherEntry to be rejected for ${host.resourcePath}")
            }
        }
    }

    @Test
    fun `NativeImageHostResourceFilter keeps only matching native resources`() {
        val cases =
            listOf(
                FilterKeepCase(
                    name = "Windows x86-64",
                    filter = filter(setOf(".dll"), setOf("win32", "windows"), x64ArchTokens),
                    keptEntries =
                        listOf(
                            "native/win32-x86-64/library.dll",
                            "native/windows-amd64/library.dll.sha256",
                            "NATIVE/WIN32/X64/LIBRARY.DLL",
                            "native/windows_x86_64_library.dll",
                        ),
                    rejectedEntries =
                        listOf(
                            "native/win32-arm64/library.dll",
                            "native/linux-x86-64/library.dll",
                            "native/win32-x86-64/library.so",
                        ),
                ),
                FilterKeepCase(
                    name = "Windows arm64",
                    filter = filter(setOf(".dll"), setOf("win32", "windows"), arm64ArchTokens),
                    keptEntries =
                        listOf(
                            "native/win32-arm64/library.dll",
                            "native/windows-aarch64/library.dll.sha256",
                            "NATIVE/WIN32/ARM64/LIBRARY.DLL",
                        ),
                    rejectedEntries =
                        listOf(
                            "native/win32-x86-64/library.dll",
                            "native/darwin-arm64/library.dll",
                            "native/win32-arm64/library.dylib",
                        ),
                ),
                FilterKeepCase(
                    name = "Linux x86-64",
                    filter = filter(setOf(".so"), setOf("linux"), x64ArchTokens),
                    keptEntries =
                        listOf(
                            "native/linux-x86-64/library.so",
                            "native/linux-amd64/library.so.sha256",
                            "NATIVE/LINUX/X64/LIBRARY.SO",
                            "native/linux_x86_64_library.so",
                        ),
                    rejectedEntries =
                        listOf(
                            "native/linux-aarch64/library.so",
                            "native/win32-x86-64/library.so",
                            "native/linux-x86-64/library.dll",
                        ),
                ),
                FilterKeepCase(
                    name = "Linux arm64",
                    filter = filter(setOf(".so"), setOf("linux"), arm64ArchTokens),
                    keptEntries =
                        listOf(
                            "native/linux-aarch64/library.so",
                            "native/linux-arm64/library.so.sha256",
                            "NATIVE/LINUX/ARM64/LIBRARY.SO",
                        ),
                    rejectedEntries =
                        listOf(
                            "native/linux-x86-64/library.so",
                            "native/darwin-aarch64/library.so",
                            "native/linux-aarch64/library.dylib",
                        ),
                ),
                FilterKeepCase(
                    name = "Darwin x86-64",
                    filter = filter(setOf(".dylib", ".jnilib"), darwinOsTokens, x64ArchTokens),
                    keptEntries =
                        listOf(
                            "native/darwin-x86-64/library.dylib",
                            "native/macos-amd64/library.jnilib.sha256",
                            "NATIVE/OSX/X64/LIBRARY.DYLIB",
                            "native/mac_x86_64_library.jnilib",
                        ),
                    rejectedEntries =
                        listOf(
                            "native/darwin-aarch64/library.dylib",
                            "native/linux-x86-64/library.dylib",
                            "native/darwin-x86-64/library.so",
                        ),
                ),
                FilterKeepCase(
                    name = "Darwin arm64",
                    filter = filter(setOf(".dylib", ".jnilib"), darwinOsTokens, arm64ArchTokens),
                    keptEntries =
                        listOf(
                            "native/darwin-aarch64/library.dylib",
                            "native/macos-arm64/library.jnilib.sha256",
                            "NATIVE/OSX/ARM64/LIBRARY.DYLIB",
                        ),
                    rejectedEntries =
                        listOf(
                            "native/darwin-x86-64/library.dylib",
                            "native/windows-aarch64/library.dylib",
                            "native/darwin-aarch64/library.dll",
                        ),
                ),
            )

        cases.forEach { case ->
            assertTrue(case.filter.keep("META-INF/services/example.Service"), case.name)
            case.keptEntries.forEach { entry ->
                assertTrue(case.filter.keep(entry), "Expected $entry to be kept for ${case.name}")
            }
            case.rejectedEntries.forEach { entry ->
                assertFalse(case.filter.keep(entry), "Expected $entry to be rejected for ${case.name}")
            }
        }
    }

    private data class Host(
        val osName: String,
        val osArch: String,
        val resourcePath: String,
        val librarySuffix: String,
    )

    private data class FilterKeepCase(
        val name: String,
        val filter: NativeImageHostResourceFilter,
        val keptEntries: List<String>,
        val rejectedEntries: List<String>,
    )

    private val x64ArchTokens = setOf("amd64", "x64", "x86-64", "x86_64")
    private val arm64ArchTokens = setOf("aarch64", "arm64")
    private val darwinOsTokens = setOf("mac", "macos", "osx", "darwin")

    private fun filter(
        librarySuffixes: Set<String>,
        osTokens: Set<String>,
        archTokens: Set<String>,
    ): NativeImageHostResourceFilter =
        NativeImageHostResourceFilter(librarySuffixes, osTokens, archTokens)

    private fun MutableList<Host>.addHosts(
        osNames: List<String>,
        osArches: List<String>,
        resourcePath: String,
        librarySuffix: String,
    ) {
        osNames.forEach { osName ->
            osArches.forEach { osArch ->
                add(Host(osName, osArch, resourcePath, librarySuffix))
            }
        }
    }
}
