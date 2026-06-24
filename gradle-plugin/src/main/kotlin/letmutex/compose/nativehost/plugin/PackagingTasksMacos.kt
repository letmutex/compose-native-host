package letmutex.compose.nativehost.plugin

import java.io.File
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider

fun configureMacOsNativeLauncher(
    project: Project,
    config: NativeLauncherConfig,
    taskProvider: TaskProvider<Exec>,
    extractTask: TaskProvider<Task>,
    buildNativeBridge: TaskProvider<Exec>,
    generateJvmArgsSwiftSource: TaskProvider<Task>,
) {
    taskProvider.configure {
        dependsOn(extractTask, buildNativeBridge, generateJvmArgsSwiftSource)
        val trackedLauncherSources = project.files(config.launcherSources)
        inputs.files(trackedLauncherSources)
        inputs.files(project.files(config.generatedSwiftSourcesDir).filter(File::exists))
        inputs.files(project.files(config.jvmArgsSwiftSourceFile).builtBy(generateJvmArgsSwiftSource))
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        inputs.file(config.bridgeLibraryFile)
        inputs.file(config.bridgeModuleFile)
        outputs.file(config.outputFile)

        doFirst {
            config.outputFile.parentFile.mkdirs()
            val generatedLauncherSources =
                (
                    swiftSources(config.generatedSwiftSourcesDir) +
                        listOf(config.jvmArgsSwiftSourceFile).filter(File::exists)
                ).sortedBy(File::getAbsolutePath)
            commandLine(
                buildList {
                    add("swiftc")
                    addAll(config.launcherSources.map(File::getAbsolutePath))
                    addAll(generatedLauncherSources.map(File::getAbsolutePath))
                    add("-I")
                    add(config.bridgeModuleFile.parentFile.absolutePath)
                    add("-L")
                    add(config.bridgeLibraryFile.parentFile.absolutePath)
                    add("-l$nativeBridgeLibraryName")
                    add("-Xlinker")
                    add("-rpath")
                    add("-Xlinker")
                    add("@executable_path/../Resources/native")
                    add("-o")
                    add(config.outputFile.absolutePath)
                    add("-framework")
                    add("Cocoa")
                    add("-framework")
                    add("Metal")
                    add("-I")
                    add("${config.javaHome}/include")
                    add("-I")
                    add("${config.javaHome}/include/darwin")
                },
            )
        }
    }
}

fun configureMacOsNativeBridge(
    project: Project,
    config: NativeLauncherConfig,
    taskProvider: TaskProvider<Exec>,
    extractTask: TaskProvider<Task>,
    generateJvmArgsSwiftSource: TaskProvider<Task>,
) {
    taskProvider.configure {
        dependsOn(extractTask, generateJvmArgsSwiftSource)
        val bridgeSourcesDir = File(config.extractedSourcesDir, "native/$hostOsPrefix")
        val sharedSwiftSourcesDir = File(config.extractedSourcesDir, "swift")
        inputs.dir(bridgeSourcesDir)
        inputs.dir(sharedSwiftSourcesDir)
        inputs.files(project.files(config.generatedSwiftSourcesDir).filter(File::exists))
        inputs.files(project.files(config.jvmArgsSwiftSourceFile).builtBy(generateJvmArgsSwiftSource))
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        outputs.file(config.bridgeLibraryFile)
        outputs.file(config.bridgeModuleFile)

        doFirst {
            config.bridgeLibraryFile.parentFile.mkdirs()
            config.bridgeModuleFile.parentFile.mkdirs()
            val bridgeSources =
                bridgeSourcesDir.listFiles()
                    ?.filter { it.isFile && (it.extension == "m" || it.extension == "h") }
                    ?.sortedBy(File::getName)
                    .orEmpty()
            val bridgeCompilationSources = bridgeSources.filter { it.extension == "m" }
            val sharedSwiftSources =
                (
                    swiftSources(sharedSwiftSourcesDir) +
                        swiftSources(config.generatedSwiftSourcesDir) +
                        listOf(config.jvmArgsSwiftSourceFile).filter(File::exists)
                )
                    .sortedBy(File::getAbsolutePath)
            check(bridgeCompilationSources.size == 1) {
                "Expected exactly one Objective-C bridge source, found ${bridgeCompilationSources.size}."
            }
            val bridgeObjectFile = File(config.bridgeLibraryFile.parentFile, "Bridge.o")
            val bridgeSource = bridgeCompilationSources.single()
            commandLine(
                "/bin/zsh",
                "-lc",
                buildString {
                    append("clang -c -fobjc-arc ")
                    append("-I ")
                    append(shellQuote("${config.javaHome}/include"))
                    append(" -I ")
                    append(shellQuote("${config.javaHome}/include/darwin"))
                    append(" ")
                    append(shellQuote(bridgeSource.absolutePath))
                    append(" -o ")
                    append(shellQuote(bridgeObjectFile.absolutePath))
                    append(" && swiftc ")
                    append(sharedSwiftSources.joinToString(" ") { shellQuote(it.absolutePath) })
                    append(" ")
                    append(shellQuote(bridgeObjectFile.absolutePath))
                    append(" -emit-library -emit-module")
                    append(" -module-name ")
                    append(shellQuote(nativeBridgeModuleName))
                    append(" -emit-module-path ")
                    append(shellQuote(config.bridgeModuleFile.absolutePath))
                    append(" -Xlinker -install_name -Xlinker ")
                    append(shellQuote("@rpath/${config.bridgeLibraryFile.name}"))
                    append(" -o ")
                    append(shellQuote(config.bridgeLibraryFile.absolutePath))
                    append(" -framework Cocoa -framework Metal")
                },
            )
        }
    }
}
