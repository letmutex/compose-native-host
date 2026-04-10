package letmutex.compose.nativehost.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project

internal fun resolveGraalVmHome(project: Project): String =
    findResolvableGraalVmHome(project)
        ?: throw GradleException(
            "Set graalvmHome, GRAALVM_HOME, org.gradle.java.home, or JAVA_HOME to a GraalVM installation before running native-image tasks.",
        )

private fun findResolvableGraalVmHome(project: Project): String? =
    project.providers.gradleProperty("graalvmHome")
        .orElse(project.providers.environmentVariable("GRAALVM_HOME"))
        .orElse(project.providers.gradleProperty("org.gradle.java.home"))
        .orElse(project.providers.environmentVariable("JAVA_HOME"))
        .orNull
