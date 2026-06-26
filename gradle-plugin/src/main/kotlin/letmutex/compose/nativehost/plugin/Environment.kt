package letmutex.compose.nativehost.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project

internal fun resolveGraalVmHome(project: Project): String =
    findResolvableGraalVmHome(project)
        ?: throw GradleException(
            "Set graalvmHome, GRAALVM_HOME, org.gradle.java.home, or JAVA_HOME to a GraalVM installation before running native-image tasks.",
        )

private fun findResolvableGraalVmHome(project: Project): String? {
    var dir: java.io.File? = project.projectDir
    var localProps: java.io.File? = null
    while (dir != null) {
        val f = java.io.File(dir, "local.properties")
        if (f.exists()) {
            localProps = f
            break
        }
        dir = dir.parentFile
    }
    if (localProps != null) {
        val props = java.util.Properties()
        localProps.inputStream().use { props.load(it) }
        val graalvmHome = props.getProperty("graalvmHome")
        if (!graalvmHome.isNullOrBlank()) {
            return graalvmHome
        }
    }
    return project.providers.gradleProperty("graalvmHome")
        .orElse(project.providers.environmentVariable("GRAALVM_HOME"))
        .orElse(project.providers.gradleProperty("org.gradle.java.home"))
        .orElse(project.providers.environmentVariable("JAVA_HOME"))
        .orNull
}
