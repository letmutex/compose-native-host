import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
                implementation(project(":compose"))
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

val composePackageName = "ComposeNativeHostAppKit"
val composePackageVersion = "1.0.0"

composeNativeHost {
    jvmTargetName.set("macos")
    appName.set("Compose Native Host AppKit")
    bundleIdentifier.set("letmutex.compose.nativehost.sample.appkit")
    nativeImage {
        mainClasses("letmutex.compose.nativehost.sample.HostedMainKt")
    }
}

compose.desktop {
    application {
        mainClass = "letmutex.compose.nativehost.sample.HostedMainKt"
        jvmArgs("-XstartOnFirstThread")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            modules("jdk.unsupported")
            packageName = composePackageName
            packageVersion = composePackageVersion
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

