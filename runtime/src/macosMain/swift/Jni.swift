import Darwin
import Foundation

let bridgeJvmProperty = "compose.native.host.bridge.path"
let runtimeClassName = "letmutex.compose.nativehost.ComposeRuntime"
let idleWaitNanos: UInt64 = 8_000_000

typealias jint = Int32
typealias jbyte = Int8

struct BundledJvmConfig {
    let classpath: String?
    let javaOptions: [String]
}

struct JavaVMOption {
    var optionString: UnsafeMutablePointer<Int8>?
    var extraInfo: UnsafeMutableRawPointer?
}

struct JavaVMInitArgs {
    var version: jint = 0x00010008
    var nOptions: jint = 0
    var options: UnsafeMutablePointer<JavaVMOption>?
    var ignoreUnrecognized: UInt8 = 0
}

typealias JNI_CreateJavaVM_Type = @convention(c) (
    UnsafeMutablePointer<UnsafeMutableRawPointer?>?,
    UnsafeMutablePointer<UnsafeMutableRawPointer?>?,
    UnsafeMutableRawPointer?
) -> jint
typealias JniFindClass = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafePointer<Int8>
) -> UnsafeMutableRawPointer?
typealias JniNewGlobalRef = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer?
typealias JniDeleteGlobalRef = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer?
) -> Void
typealias JniDeleteLocalRef = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer?
) -> Void
typealias JniNewObjectA = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeRawPointer?
) -> UnsafeMutableRawPointer?
typealias JniExceptionOccurred = @convention(c) (
    UnsafeMutableRawPointer
) -> UnsafeMutableRawPointer?
typealias JniExceptionClear = @convention(c) (
    UnsafeMutableRawPointer
) -> Void
typealias JniGetMethodID = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafePointer<Int8>,
    UnsafePointer<Int8>
) -> UnsafeMutableRawPointer?
typealias JniGetStaticMethodID = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafePointer<Int8>,
    UnsafePointer<Int8>
) -> UnsafeMutableRawPointer?
typealias JniCallObjectMethodA = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeRawPointer?
) -> UnsafeMutableRawPointer?
typealias JniCallStaticObjectMethodA = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeRawPointer?
) -> UnsafeMutableRawPointer?
typealias JniCallBooleanMethodA = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeRawPointer?
) -> UInt8
typealias JniCallVoidMethodA = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeRawPointer?
) -> Void
typealias JniCallStaticBooleanMethodA = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeRawPointer?
) -> UInt8
typealias JniCallStaticVoidMethodA = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer,
    UnsafeRawPointer?
) -> Void
typealias JniNewObjectArray = @convention(c) (
    UnsafeMutableRawPointer,
    Int32,
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer?
typealias JniSetObjectArrayElement = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer?,
    Int32,
    UnsafeMutableRawPointer?
) -> Void
typealias JniNewByteArray = @convention(c) (
    UnsafeMutableRawPointer,
    Int32
) -> UnsafeMutableRawPointer?
typealias JniSetByteArrayRegion = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafeMutableRawPointer?,
    Int32,
    Int32,
    UnsafePointer<jbyte>?
) -> Void
typealias JniNewStringUTF = @convention(c) (
    UnsafeMutableRawPointer,
    UnsafePointer<Int8>
) -> UnsafeMutableRawPointer?

struct jvalue {
    private var rawValue: UInt64 = 0

    init(l: UnsafeMutableRawPointer?) {
        rawValue = UInt64(UInt(bitPattern: l))
    }

    init(j: Int64) {
        rawValue = UInt64(bitPattern: j)
    }

    init(i: Int32) {
        rawValue = UInt64(UInt32(bitPattern: i))
    }

    init(z: Bool) {
        rawValue = z ? 1 : 0
    }

    init(f: Float) {
        let bitPattern = f.bitPattern
        withUnsafeBytes(of: bitPattern) { bytes in
            withUnsafeMutableBytes(of: &rawValue) { storage in
                storage[..<MemoryLayout<UInt32>.size].copyBytes(from: bytes)
            }
        }
    }
}

func jniClassName(_ className: String) -> String {
    className.replacingOccurrences(of: ".", with: "/")
}

func loadSystemClass(
    envRaw: UnsafeMutableRawPointer,
    findClass: JniFindClass,
    getMethodID: JniGetMethodID,
    getStaticMethodID: JniGetStaticMethodID,
    callObjectMethodA: JniCallObjectMethodA,
    callStaticObjectMethodA: JniCallStaticObjectMethodA,
    newStringUTF: JniNewStringUTF,
    deleteLocalRef: JniDeleteLocalRef,
    className: String
) -> UnsafeMutableRawPointer? {
    guard let classLoaderClass = findClass(envRaw, "java/lang/ClassLoader") else {
        return nil
    }
    defer {
        deleteLocalRef(envRaw, classLoaderClass)
    }

    guard let getSystemClassLoader = getStaticMethodID(
        envRaw,
        classLoaderClass,
        "getSystemClassLoader",
        "()Ljava/lang/ClassLoader;"
    ) else {
        return nil
    }

    let classLoader = callStaticObjectMethodA(envRaw, classLoaderClass, getSystemClassLoader, nil)
    guard let classLoader else {
        return nil
    }
    defer {
        deleteLocalRef(envRaw, classLoader)
    }

    guard let loadClass = getMethodID(
        envRaw,
        classLoaderClass,
        "loadClass",
        "(Ljava/lang/String;)Ljava/lang/Class;"
    ),
    let jClassName = newStringUTF(envRaw, className) else {
        return nil
    }
    defer {
        deleteLocalRef(envRaw, jClassName)
    }

    let loadArgs = [jvalue(l: jClassName)]
    return loadArgs.withUnsafeBufferPointer { buffer in
        callObjectMethodA(envRaw, classLoader, loadClass, UnsafeRawPointer(buffer.baseAddress))
    }
}

func sleepNanos(_ nanos: UInt64) {
    if nanos == 0 {
        return
    }
    var duration = timespec(
        tv_sec: Int(nanos / 1_000_000_000),
        tv_nsec: Int(nanos % 1_000_000_000)
    )
    while true {
        var remaining = timespec()
        if nanosleep(&duration, &remaining) == 0 {
            break
        }
        if errno != EINTR {
            break
        }
        duration = remaining
    }
}

extension String {
    func removingPrefix(_ prefix: String) -> String? {
        guard hasPrefix(prefix) else {
            return nil
        }
        return String(dropFirst(prefix.count))
    }
}
