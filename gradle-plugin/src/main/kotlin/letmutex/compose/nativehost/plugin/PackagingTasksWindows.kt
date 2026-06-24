package letmutex.compose.nativehost.plugin

import java.io.File
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider

fun configureWindowsNativeLauncher(
    project: Project,
    config: NativeLauncherConfig,
    taskProvider: TaskProvider<Exec>,
    extractTask: TaskProvider<Task>,
    generateJvmArgsSwiftSource: TaskProvider<Task>,
) {
    taskProvider.configure {
        dependsOn(extractTask, generateJvmArgsSwiftSource)
        val trackedLauncherSources =
            project.files(config.launcherSources) + project.files(File(config.extractedSourcesDir, "cpp"))
        inputs.files(trackedLauncherSources)
        inputs.files(project.files(config.generatedSwiftSourcesDir).filter(File::exists))
        inputs.files(project.files(config.jvmArgsSwiftSourceFile).builtBy(generateJvmArgsSwiftSource))
        inputs.property("composeNativeHostJvmArgs", config.jvmArgs)
        outputs.file(config.outputFile)

        doFirst {
            config.outputFile.parentFile.mkdirs()
            val isEnvActive = !System.getenv("INCLUDE").isNullOrBlank() && !System.getenv("LIB").isNullOrBlank()
            val jniHeadersDir = File(config.javaHome, "include").absolutePath
            val jniWinHeadersDir = File(config.javaHome, "include/win32").absolutePath
            val objDir = project.layout.buildDirectory.dir("tmp/native-obj/launcher").get().asFile
            objDir.mkdirs()
            val compileCmd = buildString {
                if (!isEnvActive) {
                    val vcvars64 = locateVcvars64(project).absolutePath
                    append("call \"")
                    append(vcvars64)
                    append("\" && ")
                }
                append("cl.exe /nologo /EHsc /O2 ")
                val sources = config.launcherSources + cppSources(File(config.extractedSourcesDir, "cpp"))
                sources.forEach { source ->
                    append("\"")
                    append(source.absolutePath)
                    append("\" ")
                }
                append("/Fe:\"")
                append(config.outputFile.absolutePath)
                append("\" ")
                append("/Fo:\"")
                append(objDir.absolutePath)
                append("/\" ")
                append("/Fd:\"")
                append(objDir.absolutePath)
                append("/\" ")
                append("/I\"")
                append(File(config.extractedSourcesDir, "cpp").absolutePath)
                append("\" ")
                append("/I\"")
                append(File(config.extractedSourcesDir, "native/windows").absolutePath)
                append("\" ")
                append("/I\"")
                append(jniHeadersDir)
                append("\" /I\"")
                append(jniWinHeadersDir)
                append("\" ")
                append("/link /nologo ")
                val manifestFile = File(objDir, "app.manifest")
                manifestFile.writeText(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<assembly manifestVersion=\"1.0\" xmlns=\"urn:schemas-microsoft-com:asm.v1\" xmlns:asmv3=\"urn:schemas-microsoft-com:asm.v3\">\n" +
                    "    <dependency>\n" +
                    "        <dependentAssembly>\n" +
                    "            <assemblyIdentity type=\"win32\" name=\"Microsoft.Windows.Common-Controls\"\n" +
                    "                version=\"6.0.0.0\" processorArchitecture=\"*\"\n" +
                    "                publicKeyToken=\"6595b64144ccf1df\" language=\"*\"/>\n" +
                    "        </dependentAssembly>\n" +
                    "    </dependency>\n" +
                    "    <compatibility xmlns=\"urn:schemas-microsoft-com:compatibility.v1\">\n" +
                    "        <application>\n" +
                    "            <!-- Windows 10 and Windows 11 -->\n" +
                    "            <supportedOS Id=\"{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}\"/>\n" +
                    "            <!-- Windows 8.1 -->\n" +
                    "            <supportedOS Id=\"{1f676c76-80e1-4239-95bb-83d0f6d0da78}\"/>\n" +
                    "            <!-- Windows 8 -->\n" +
                    "            <supportedOS Id=\"{4a2f28e3-53b9-4441-ba9c-d69d4a4a6e38}\"/>\n" +
                    "            <!-- Windows 7 -->\n" +
                    "            <supportedOS Id=\"{35138b9a-5d96-4fbd-8e2d-a2440225f93a}\"/>\n" +
                    "        </application>\n" +
                    "    </compatibility>\n" +
                    "</assembly>\n"
                )
                append("/MANIFEST:EMBED /MANIFESTINPUT:\"")
                append(manifestFile.absolutePath)
                append("\" ")
                append("/IMPLIB:\"")
                append(File(objDir, config.outputFile.nameWithoutExtension + ".lib").absolutePath)
                append("\" ")
                append("user32.lib gdi32.lib d3d12.lib dxgi.lib uxtheme.lib")
            }
            commandLine("cmd.exe", "/c", compileCmd)
        }
    }
}

fun configureWindowsNativeBridge(
    project: Project,
    config: NativeLauncherConfig,
    taskProvider: TaskProvider<Exec>,
    extractTask: TaskProvider<Task>,
    generateJvmArgsSwiftSource: TaskProvider<Task>,
    buildNativeLauncher: TaskProvider<Exec>,
) {
    taskProvider.configure {
        dependsOn(extractTask, generateJvmArgsSwiftSource, buildNativeLauncher)
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
            val isEnvActive = !System.getenv("INCLUDE").isNullOrBlank() && !System.getenv("LIB").isNullOrBlank()
            val jniHeadersDir = File(config.javaHome, "include").absolutePath
            val jniWinHeadersDir = File(config.javaHome, "include/win32").absolutePath
            val launcherObjDir = project.layout.buildDirectory.dir("tmp/native-obj/launcher").get().asFile
            val launcherLib = File(launcherObjDir, config.outputFile.nameWithoutExtension + ".lib").absolutePath
            val objDir = project.layout.buildDirectory.dir("tmp/native-obj/bridge").get().asFile
            objDir.mkdirs()
            
            val compileCmd = buildString {
                if (!isEnvActive) {
                    val vcvars64 = locateVcvars64(project).absolutePath
                    append("call \"")
                    append(vcvars64)
                    append("\" && ")
                }
                append("cl.exe /nologo /EHsc /O2 /LD ")
                append("\"")
                append(File(bridgeSourcesDir, "Bridge.cpp").absolutePath)
                append("\" ")
                append("/Fe:\"")
                append(config.bridgeLibraryFile.absolutePath)
                append("\" ")
                append("/Fo:\"")
                append(objDir.absolutePath)
                append("/\" ")
                append("/Fd:\"")
                append(objDir.absolutePath)
                append("/\" ")
                append("/I\"")
                append(jniHeadersDir)
                append("\" /I\"")
                append(jniWinHeadersDir)
                append("\" /I\"")
                append(File(config.extractedSourcesDir, "native/windows").absolutePath)
                append("\" /I\"")
                append(File(config.extractedSourcesDir, "cpp").absolutePath)
                append("\" ")
                append("\"")
                append(launcherLib)
                append("\" ")
                append("/link /nologo ")
                append("/IMPLIB:\"")
                append(File(objDir, "bridge.lib").absolutePath)
                append("\"")
            }
            commandLine("cmd.exe", "/c", compileCmd)
        }
    }
}
