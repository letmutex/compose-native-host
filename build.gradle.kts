import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

val publishedGroup = providers.gradleProperty("GROUP").get()
val publishedVersion = providers.gradleProperty("VERSION_NAME").get()

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.jmh) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = publishedGroup
    version = publishedVersion
}

