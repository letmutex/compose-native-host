package letmutex.compose.nativehost.plugin

import java.io.File

internal fun jniConfigEntry(
    className: String,
    extraBody: String = "",
): String =
    buildString {
        append("  {\"name\":\"")
        append(className)
        append("\",\"allDeclaredMethods\":true,\"allPublicMethods\":true")
        append(",\"allDeclaredConstructors\":true,\"allPublicConstructors\":true")
        append(",\"allDeclaredFields\":true,\"allPublicFields\":true")
        if (extraBody.isNotEmpty()) {
            append(",")
            append(extraBody)
        }
        append("}")
    }

internal fun generatedHelperJavaFile(config: NativeImageExperimentConfig): File {
    val packageName = config.helperMainClass.substringBeforeLast('.', missingDelimiterValue = "")
    val simpleName = config.helperMainClass.substringAfterLast('.')
    val relativePath =
        if (packageName.isEmpty()) {
            "$simpleName.java"
        } else {
            packageName.replace('.', '/') + "/$simpleName.java"
        }
    return File(config.generatedHelperSourcesDir, relativePath)
}

internal fun renderNativeImageHelperJava(config: NativeImageExperimentConfig): String {
    val packageName = config.helperMainClass.substringBeforeLast('.', missingDelimiterValue = "")
    val simpleName = config.helperMainClass.substringAfterLast('.')
    val header = renderNativeImageHelperHeader(packageName, simpleName)
    val body = renderNativeImageHelperBody(config.mainClasses)
    return listOf(header, body).joinToString(separator = "\n")
}

private fun renderNativeImageHelperHeader(
    packageName: String,
    simpleName: String,
): String =
    """
        ${if (packageName.isNotEmpty()) "package $packageName;\n\n        " else ""}
        import letmutex.compose.nativehost.ComposeRuntime;
        import org.graalvm.nativeimage.ObjectHandle;
        import org.graalvm.nativeimage.ObjectHandles;
        import org.graalvm.nativeimage.IsolateThread;
        import org.graalvm.nativeimage.c.function.CEntryPoint;
        import org.graalvm.nativeimage.c.type.CCharPointer;
        import org.graalvm.nativeimage.c.type.CCharPointerPointer;
        import org.graalvm.nativeimage.c.type.CTypeConversion;
        import org.graalvm.word.WordFactory;

        public final class $simpleName {
            private static final ObjectHandles runtimeHandles = ObjectHandles.getGlobal();

            private $simpleName() {
            }

            public static void main(String[] args) {
            }
        
    """.trimIndent()

private fun renderNativeImageHelperBody(mainClasses: List<String>): String =
    """
        private static String[] toJavaStringArray(CCharPointerPointer pointers, int count) {
            if (pointers.isNull() || count <= 0) {
                return new String[0];
            }
            String[] values = new String[count];
            for (int index = 0; index < count; index++) {
                CCharPointer pointer = pointers.read(index);
                values[index] = pointer.isNull() ? "" : CTypeConversion.toJavaString(pointer);
            }
            return values;
        }

        private static String toJavaStringOrNull(CCharPointer pointer) {
            if (pointer.isNull()) {
                return null;
            }
            return CTypeConversion.toJavaString(pointer);
        }

        private static byte[] toJavaByteArrayOrNull(CCharPointer pointer, int count) {
            if (pointer.isNull() || count <= 0) {
                return null;
            }
            byte[] values = new byte[count];
            for (int index = 0; index < count; index++) {
                values[index] = pointer.read(index);
            }
            return values;
        }

        private static ObjectHandle objectHandle(long rawValue) {
            return WordFactory.pointer(rawValue);
        }

        private static ComposeRuntime runtimeFromHandle(long rawValue) {
            if (rawValue == 0L) {
                return null;
            }
            return (ComposeRuntime) runtimeHandles.get(objectHandle(rawValue));
        }

        @CEntryPoint(name = "composeNativeHostRuntimeInitialize")
        static void initialize(IsolateThread thread) {
            ComposeRuntime.initialize();
        }

        @CEntryPoint(name = "composeNativeHostRuntimeCreate")
        static long createRuntime(IsolateThread thread, long runtimeId, int profileRenderingEnabled) {
            ComposeRuntime runtime = new ComposeRuntime(runtimeId, profileRenderingEnabled != 0);
            return runtimeHandles.create(runtime).rawValue();
        }

        @CEntryPoint(name = "$sharedLibraryBindMainSymbol")
        static int bindHostedMain(IsolateThread thread, long runtimeHandle, CCharPointer mainClassPointer) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null || mainClassPointer.isNull()) {
                return 0;
            }
            String mainClassName = CTypeConversion.toJavaString(mainClassPointer);
            ComposeRuntime.enterCurrentRuntime(runtime);
            try {
                switch (mainClassName) {
                    ${mainClasses.joinToString(separator = "\n") { mainClassName ->
                        "                    case \"$mainClassName\": $mainClassName.main(); break;"
                    }}
                    default:
                        return 0;
                }
                return runtime.isContentBound() ? 1 : 0;
            } finally {
                ComposeRuntime.exitCurrentRuntime();
            }
        }

        @CEntryPoint(name = "composeNativeHostRuntimeStart")
        static int startRuntime(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            runtime.startRuntime();
            return 1;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeRequestFrame")
        static void requestFrame(IsolateThread thread, long runtimeHandle, long vsyncNanos) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            runtime.requestFrame(vsyncNanos);
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragEntered")
        static int handleExternalDragEntered(
            IsolateThread thread,
            long runtimeHandle,
            int x,
            int y,
            int action,
            int payloadKind,
            long timestampMillis,
            CCharPointerPointer files,
            int fileCount,
            CCharPointer textPointer,
            CCharPointer imageBytesPointer,
            int imageBytesCount,
            CCharPointer imageFormatPointer
        ) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            return runtime.handleExternalDragEntered(
                x,
                y,
                action,
                payloadKind,
                timestampMillis,
                toJavaStringArray(files, fileCount),
                toJavaStringOrNull(textPointer),
                toJavaByteArrayOrNull(imageBytesPointer, imageBytesCount),
                toJavaStringOrNull(imageFormatPointer)
            ) ? 1 : 0;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragMoved")
        static int handleExternalDragMoved(
            IsolateThread thread,
            long runtimeHandle,
            int x,
            int y,
            int action,
            int payloadKind,
            long timestampMillis,
            CCharPointerPointer files,
            int fileCount,
            CCharPointer textPointer,
            CCharPointer imageBytesPointer,
            int imageBytesCount,
            CCharPointer imageFormatPointer
        ) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            return runtime.handleExternalDragMoved(
                x,
                y,
                action,
                payloadKind,
                timestampMillis,
                toJavaStringArray(files, fileCount),
                toJavaStringOrNull(textPointer),
                toJavaByteArrayOrNull(imageBytesPointer, imageBytesCount),
                toJavaStringOrNull(imageFormatPointer)
            ) ? 1 : 0;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragExited")
        static void handleExternalDragExited(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            runtime.handleExternalDragExited();
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDragEnded")
        static void handleExternalDragEnded(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            runtime.handleExternalDragEnded();
        }

        @CEntryPoint(name = "composeNativeHostRuntimeHandleExternalDrop")
        static int handleExternalDrop(
            IsolateThread thread,
            long runtimeHandle,
            int x,
            int y,
            int action,
            int payloadKind,
            long timestampMillis,
            CCharPointerPointer files,
            int fileCount,
            CCharPointer textPointer,
            CCharPointer imageBytesPointer,
            int imageBytesCount,
            CCharPointer imageFormatPointer
        ) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return 0;
            }
            return runtime.handleExternalDrop(
                x,
                y,
                action,
                payloadKind,
                timestampMillis,
                toJavaStringArray(files, fileCount),
                toJavaStringOrNull(textPointer),
                toJavaByteArrayOrNull(imageBytesPointer, imageBytesCount),
                toJavaStringOrNull(imageFormatPointer)
            ) ? 1 : 0;
        }

        @CEntryPoint(name = "composeNativeHostRuntimeClose")
        static void closeRuntime(IsolateThread thread, long runtimeHandle) {
            ComposeRuntime runtime = runtimeFromHandle(runtimeHandle);
            if (runtime == null) {
                return;
            }
            try {
                runtime.closeRuntime();
            } finally {
                runtimeHandles.destroy(objectHandle(runtimeHandle));
            }
        }
    }
    """.trimIndent()
