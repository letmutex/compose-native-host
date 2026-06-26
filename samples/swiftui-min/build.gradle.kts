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
    jvm("macos")

    sourceSets {
        val commonMain by getting
        val desktopMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.github.letmutex.compose-native-host:runtime")
                implementation(compose.desktop.currentOs)
            }
        }
        val macosMain by getting {
            dependsOn(desktopMain)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

val composePackageName = "Sample"
val composePackageVersion = "1.0.0"

composeNativeHost {
    jvmTargetName.set("macos")
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
            modules("jdk.unsupported")
            packageName = composePackageName
            packageVersion = composePackageVersion
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

