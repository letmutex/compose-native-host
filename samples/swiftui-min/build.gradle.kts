import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("io.github.letmutex.compose.nativehost")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation("io.github.letmutex.compose-native-host:runtime")
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

val composePackageName = "Sample"
val composePackageVersion = "1.0.0"

composeNativeHost {
    appName.set("Sample")
    bundleIdentifier.set("example.sample")
    nativeImage {
        mainClasses("example.MainKt")
    }
}

compose.desktop {
    application {
        mainClass = "example.MainKt"
        jvmArgs("-XstartOnFirstThread")
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = composePackageName
            packageVersion = composePackageVersion
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
