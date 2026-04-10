pluginManagement {
    includeBuild("..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "samples"

includeBuild("..")

include(":appkit")
include(":compose")
include(":mixed")
include(":swiftui")
include(":swiftui-min")
