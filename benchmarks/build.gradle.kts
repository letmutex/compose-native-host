import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val jmhInclude = providers.gradleProperty("jmhInclude").orNull

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":runtime"))
    jmh(libs.jmh.core)
    kaptJmh(libs.jmh.generator.annprocess)
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

jmh {
    if (jmhInclude != null) {
        includes.set(listOf(jmhInclude))
    }
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    resultFormat.set("JSON")
}
