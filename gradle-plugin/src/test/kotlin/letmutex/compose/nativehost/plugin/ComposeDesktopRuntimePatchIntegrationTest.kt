package letmutex.compose.nativehost.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ComposeDesktopRuntimePatchIntegrationTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `preparePatchedComposeDesktopRuntimeClasspath strips patched classes from matching owner jars`() {
        val fixture = TestFixtureProject(projectDir)
        fixture.writeBaseProject()
        fixture.publishMavenModule(
            group = "test.fixture",
            module = "native-host-runtime",
            version = "1.0",
            jarName = "native-host-runtime-1.0.jar",
            classEntries = setOf(
                "letmutex/compose/nativehost/ComposeRuntime.class",
                "androidx/compose/ui/Foo.class",
                "androidx/lifecycle/Bar.class",
                "org/jetbrains/skiko/Baz.class",
                "keep/RuntimeKeep.class",
            ),
        )
        fixture.publishIvyModule(
            organisation = "org.jetbrains.compose.ui",
            module = "ui-desktop",
            version = "1.0",
            artifact = "ui-desktop-awt",
            classEntries = setOf(
                "androidx/compose/ui/Foo.class",
                "keep/UiKeep.class",
            ),
        )
        fixture.publishMavenModule(
            group = "androidx.lifecycle",
            module = "lifecycle-runtime-desktop",
            version = "1.0",
            jarName = "lifecycle-runtime-desktop-1.0.jar",
            classEntries = setOf(
                "androidx/lifecycle/Bar.class",
                "keep/LifecycleKeep.class",
            ),
        )
        fixture.publishMavenModule(
            group = "test.fixture",
            module = "unrelated",
            version = "1.0",
            jarName = "unrelated-1.0.jar",
            classEntries = setOf(
                "androidx/compose/ui/Foo.class",
                "keep/UnrelatedKeep.class",
            ),
        )

        val result =
            fixture.runner(
                "preparePatchedComposeDesktopRuntimeClasspath",
            ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":preparePatchedComposeDesktopRuntimeClasspath")?.outcome)

        val stagedDir = fixture.stagedRuntimeDir()
        val runtimeJarMap = fixture.runtimeJarMap()
        assertTrue(runtimeJarMap.exists())
        val stagedJarsByOriginal =
            runtimeJarMap.readLines()
                .filter(String::isNotBlank)
                .associate { line ->
                    val parts = line.split("|")
                    parts[1] to (stagedDir / parts[0])
                }

        val runtimeJar = stagedJarsByOriginal.getValue("native-host-runtime-1.0.jar")
        val composeJar = stagedJarsByOriginal.getValue("ui-desktop-awt-1.0.jar")
        val lifecycleJar = stagedJarsByOriginal.getValue("lifecycle-runtime-desktop-1.0.jar")
        val unrelatedJar = stagedJarsByOriginal.getValue("unrelated-1.0.jar")

        assertJarContains(runtimeJar, "letmutex/compose/nativehost/ComposeRuntime.class")
        assertJarContains(runtimeJar, "androidx/compose/ui/Foo.class")
        assertJarContains(runtimeJar, "androidx/lifecycle/Bar.class")
        assertJarContains(runtimeJar, "org/jetbrains/skiko/Baz.class")

        assertJarMissing(composeJar, "androidx/compose/ui/Foo.class")
        assertJarContains(composeJar, "keep/UiKeep.class")

        assertJarMissing(lifecycleJar, "androidx/lifecycle/Bar.class")
        assertJarContains(lifecycleJar, "keep/LifecycleKeep.class")

        assertJarContains(unrelatedJar, "androidx/compose/ui/Foo.class")
        assertJarContains(unrelatedJar, "keep/UnrelatedKeep.class")
    }

    @Test
    fun `preparePatchedComposeDesktopRuntimeClasspath fails when marker runtime jar is missing`() {
        val fixture = TestFixtureProject(projectDir)
        fixture.writeBaseProject()
        fixture.publishIvyModule(
            organisation = "org.jetbrains.compose.ui",
            module = "ui-desktop",
            version = "1.0",
            artifact = "ui-desktop-awt",
            classEntries = setOf("androidx/compose/ui/Foo.class"),
        )
        fixture.publishMavenModule(
            group = "androidx.lifecycle",
            module = "lifecycle-runtime-desktop",
            version = "1.0",
            jarName = "lifecycle-runtime-desktop-1.0.jar",
            classEntries = setOf("androidx/lifecycle/Bar.class"),
        )
        fixture.publishMavenModule(
            group = "test.fixture",
            module = "unrelated",
            version = "1.0",
            jarName = "unrelated-1.0.jar",
            classEntries = setOf("keep/UnrelatedKeep.class"),
        )

        val result =
            fixture.runner(
                "preparePatchedComposeDesktopRuntimeClasspath",
                "-PincludeRuntime=false",
            ).buildAndFail()

        assertTrue(result.output.contains("Expected exactly one native host runtime jar"), result.output)
        assertTrue(result.output.contains("found 0"), result.output)
    }

    @Test
    fun `preparePatchedComposeDesktopRuntimeClasspath fails when marker runtime jar is duplicated`() {
        val fixture = TestFixtureProject(projectDir)
        fixture.writeBaseProject()
        fixture.publishMavenModule(
            group = "test.fixture",
            module = "native-host-runtime",
            version = "1.0",
            jarName = "native-host-runtime-1.0.jar",
            classEntries = setOf("letmutex/compose/nativehost/ComposeRuntime.class"),
        )
        fixture.publishMavenModule(
            group = "test.fixture",
            module = "native-host-runtime-copy",
            version = "1.0",
            jarName = "native-host-runtime-copy-1.0.jar",
            classEntries = setOf("letmutex/compose/nativehost/ComposeRuntime.class"),
        )
        fixture.publishIvyModule(
            organisation = "org.jetbrains.compose.ui",
            module = "ui-desktop",
            version = "1.0",
            artifact = "ui-desktop-awt",
            classEntries = setOf("androidx/compose/ui/Foo.class"),
        )
        fixture.publishMavenModule(
            group = "androidx.lifecycle",
            module = "lifecycle-runtime-desktop",
            version = "1.0",
            jarName = "lifecycle-runtime-desktop-1.0.jar",
            classEntries = setOf("androidx/lifecycle/Bar.class"),
        )
        fixture.publishMavenModule(
            group = "test.fixture",
            module = "unrelated",
            version = "1.0",
            jarName = "unrelated-1.0.jar",
            classEntries = setOf("keep/UnrelatedKeep.class"),
        )

        val result =
            fixture.runner(
                "preparePatchedComposeDesktopRuntimeClasspath",
                "-PincludeDuplicateRuntime=true",
            ).buildAndFail()

        assertTrue(result.output.contains("Expected exactly one native host runtime jar"))
        assertTrue(result.output.contains("found 2"))
    }

    private fun assertJarContains(jar: Path, entryName: String) {
        ZipFile(jar.toFile()).use { zip ->
            assertTrue(zip.getEntry(entryName) != null, "Expected $entryName in $jar")
        }
    }

    private fun assertJarMissing(jar: Path, entryName: String) {
        ZipFile(jar.toFile()).use { zip ->
            assertFalse(zip.getEntry(entryName) != null, "Did not expect $entryName in $jar")
        }
    }
}

private class TestFixtureProject(
    private val rootDir: Path,
) {
    private val mavenRepoDir = rootDir / "repo-maven"
    private val ivyRepoDir = rootDir / "repo-ivy"

    fun writeBaseProject() {
        (rootDir / "settings.gradle.kts").writeText(
            """
            rootProject.name = "runtime-patch-test"
            """.trimIndent() + "\n",
        )
        (rootDir / "build.gradle.kts").writeText(
            """
            import org.gradle.jvm.tasks.Jar

            plugins {
                id("io.github.letmutex.compose.nativehost")
            }

            repositories {
                maven(url = uri(${quoted(mavenRepoDir)}))
                ivy {
                    url = uri(${quoted(ivyRepoDir)})
                    patternLayout {
                        artifact("[organisation]/[module]/[revision]/[artifact]-[revision].[ext]")
                        ivy("[organisation]/[module]/[revision]/ivy-[revision].xml")
                    }
                    metadataSources {
                        ivyDescriptor()
                    }
                }
            }

            val desktopRuntimeClasspath by configurations.creating

            tasks.register<Jar>("desktopJar")

            composeNativeHost {
                appName.set("TestApp")
                bundleIdentifier.set("dev.test.runtimepatch")
            }

            dependencies {
                if (providers.gradleProperty("includeRuntime").orNull != "false") {
                    desktopRuntimeClasspath("test.fixture:native-host-runtime:1.0")
                }
                if (providers.gradleProperty("includeDuplicateRuntime").orNull == "true") {
                    desktopRuntimeClasspath("test.fixture:native-host-runtime-copy:1.0")
                }
                desktopRuntimeClasspath("org.jetbrains.compose.ui:ui-desktop:1.0")
                desktopRuntimeClasspath("androidx.lifecycle:lifecycle-runtime-desktop:1.0")
                desktopRuntimeClasspath("test.fixture:unrelated:1.0")
            }
            """.trimIndent() + "\n",
        )
    }

    fun publishMavenModule(
        group: String,
        module: String,
        version: String,
        jarName: String,
        classEntries: Set<String>,
    ) {
        val moduleDir =
            mavenRepoDir.resolve(group.replace('.', '/'))
                .resolve(module)
                .resolve(version)
        Files.createDirectories(moduleDir)
        writeJar(moduleDir.resolve(jarName), classEntries)
        moduleDir.resolve("$module-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$module</artifactId>
              <version>$version</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent() + "\n",
        )
    }

    fun publishIvyModule(
        organisation: String,
        module: String,
        version: String,
        artifact: String,
        classEntries: Set<String>,
    ) {
        val moduleDir = ivyRepoDir.resolve(organisation).resolve(module).resolve(version)
        Files.createDirectories(moduleDir)
        writeJar(moduleDir.resolve("$artifact-$version.jar"), classEntries)
        moduleDir.resolve("ivy-$version.xml").writeText(
            """
            <ivy-module version="2.0">
              <info organisation="$organisation" module="$module" revision="$version"/>
              <publications>
                <artifact name="$artifact" type="jar" ext="jar"/>
              </publications>
            </ivy-module>
            """.trimIndent() + "\n",
        )
    }

    fun stagedRuntimeDir(): Path =
        rootDir /
            "build/generated/compose-native-host/runtime-classpath/TestApp.app"

    fun runtimeJarMap(): Path =
        stagedRuntimeDir().resolve("runtime-jars.map")

    fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(rootDir.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun writeJar(
        jarPath: Path,
        classEntries: Set<String>,
    ) {
        jarPath.parent.createDirectories()
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zip ->
            classEntries.sorted().forEach { entryName ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
    }
}

private fun quoted(path: Path): String =
    "\"${path.toAbsolutePath().toString().replace("\\", "\\\\")}\""
