import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    alias(libs.plugins.maven.publish)
}

gradlePlugin {
    plugins {
        create("composeNativeHost") {
            id = "io.github.letmutex.compose.nativehost"
            implementationClass = "letmutex.compose.nativehost.plugin.ComposeNativeHostPlugin"
        }
    }
}

val packageHostSources by tasks.registering(Zip::class) {
    archiveFileName.set("native-host-sources.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/resources"))
    from("../runtime/src/macosMain/swift") {
        include("**/*.swift")
        into("swift")
    }
    from("../runtime/src/macosMain/native") {
        include("*.m", "*.h")
        into("native")
    }
}

sourceSets.main {
    resources.srcDir(packageHostSources.flatMap { it.destinationDirectory })
}

tasks.named("processResources") {
    dependsOn(packageHostSources)
}

tasks.withType<Jar>().configureEach {
    if (name == "sourcesJar") {
        dependsOn(packageHostSources)
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
