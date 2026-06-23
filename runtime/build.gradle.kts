import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.maven.publish)
}
kotlin {
    val hostOs = when {
        System.getProperty("os.name").lowercase().contains("win") -> "windows"
        System.getProperty("os.name").lowercase().contains("mac") -> "macos"
        else -> "desktop"
    }

    jvm(hostOs)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.foundation)
                implementation(libs.jctools.core)
            }
        }
        val desktopMain by creating {
            dependsOn(commonMain)
        }
        if (hostOs == "windows") {
            val windowsMain by getting {
                dependsOn(desktopMain)
                dependencies {
                    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.37.4")
                }
            }
        }
        if (hostOs == "macos") {
            val macosMain by getting {
                dependsOn(desktopMain)
                dependencies {
                    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.9.37.4")
                    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.9.37.4")
                }
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val desktopTest by creating {
            dependsOn(commonTest)
        }
        if (hostOs == "windows") {
            val windowsTest by getting {
                dependsOn(desktopTest)
            }
        }
        if (hostOs == "macos") {
            val macosTest by getting {
                dependsOn(desktopTest)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
