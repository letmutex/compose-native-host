import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("io.github.letmutex.compose.nativehost")
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
                implementation(project(":compose"))
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

composeNativeHost {
    jvmTargetName.set(hostOs)
    appName.set("Compose Native Host Mixed")
    bundleIdentifier.set("letmutex.compose.nativehost.sample.mixed")
    nativeImage {
        mainClasses("letmutex.compose.nativehost.sample.HostedMainKt")
    }
}

compose.desktop {
    application {
        mainClass = "letmutex.compose.nativehost.sample.HostedMainKt"
        jvmArgs("-XstartOnFirstThread")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            modules("jdk.unsupported")
            packageName = "ComposeNativeHostMixed"
            packageVersion = "1.0.0"
            windows {
                shortcut = true
                menu = true
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

