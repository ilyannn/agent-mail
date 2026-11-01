// Timing notes for CLI calls and UI updates: always to the unified log
// (subsystem = bundle id, category "timing"), and to stderr with a Unix
// timestamp when AGENT_MAIL_TIMING is set, for latency measurements.
import Foundation
import os

enum Timing {
    private static let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "AgentMail", category: "timing")
    private static let toStderr = ProcessInfo.processInfo.environment["AGENT_MAIL_TIMING"] != nil

    static func note(_ message: String) {
        logger.info("\(message, privacy: .public)")
        guard toStderr else { return }
        let line = String(format: "[timing %.3f] ", Date().timeIntervalSince1970) + message + "\n"
        FileHandle.standardError.write(Data(line.utf8))
    }

    static func ms(_ d: Duration) -> String {
        let c = d.components
        return String(format: "%.0f ms", Double(c.seconds) * 1000 + Double(c.attoseconds) / 1e15)
    }
}
