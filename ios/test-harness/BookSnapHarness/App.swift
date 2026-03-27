import SwiftUI

/// Minimal app shell — only exists so XCTest can run against a host application.
@main
struct BookSnapHarnessApp: App {
    var body: some Scene {
        WindowGroup {
            Text("BookSnap Test Harness")
        }
    }
}
