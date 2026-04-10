import ComposeNativeHost
import SwiftUI

private let sampleJvmMainClass = "example.MainKt"

final class SampleAppDelegate: ComposeAppDelegateBase {
    private let runtimeConfiguration = ComposeRuntimeConfiguration(
        kotlinMainClass: sampleJvmMainClass
    )

    override init() {
        super.init(configuration: ComposeHostConfiguration(startups: [.jvm, .sharedLibrary()]))
    }

    fileprivate lazy var runtime = makeComposeRuntime(
        configuration: runtimeConfiguration
    )
}

@main
struct SampleApp: App {
    @NSApplicationDelegateAdaptor(SampleAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ComposeView(runtime: appDelegate.runtime)
        }
    }
}
