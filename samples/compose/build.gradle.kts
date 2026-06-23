import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val hostOs = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows"
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    else -> "desktop"
}

kotlin {
    jvm(hostOs)

    sourceSets {
        val commonMain by getting
        val desktopMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.github.letmutex.compose-native-host:runtime")
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(compose.desktop.currentOs)
            }
        }
        if (hostOs == "windows") {
            val windowsMain by getting {
                dependsOn(desktopMain)
            }
        }
        if (hostOs == "macos") {
            val macosMain by getting {
                dependsOn(desktopMain)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

compose.desktop {
    application {
        mainClass = "letmutex.compose.nativehost.sample.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            modules("jdk.unsupported")
            packageName = "ComposeNativeHostCompose"
            packageVersion = "1.0.0"
        }
    }
}

