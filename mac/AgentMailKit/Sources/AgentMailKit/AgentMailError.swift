// Errors the app shows with presentError: description, reason and a
// recovery suggestion, never as a notification.
import Foundation

public enum AgentMailError: LocalizedError, Equatable, Sendable {
    /// No agent-mail executable at any of the searched places.
    case cliNotFound(searched: [String])
    /// The executable exists but could not be started.
    case launchFailed(path: String, reason: String)
    /// The CLI ran and exited non-zero; `stderr` is what it said.
    case exit(command: String, code: Int32, stderr: String)
    case timedOut(command: String, seconds: Int)
    /// The CLI's output wasn't the JSON we expect.
    case decode(what: String, detail: String)
    /// `status --json` reported a schema this app doesn't know.
    case schemaMismatch(found: Int?, supported: Int)

    public var errorDescription: String? {
        switch self {
        case .cliNotFound:
            "The agent-mail command wasn’t found."
        case let .launchFailed(path, _):
            "Agent Mail couldn’t run \(path)."
        case let .exit(command, code, stderr):
            Self.firstLine(stderr).map { "agent-mail \(command) failed: \($0)" }
                ?? "agent-mail \(command) failed with exit code \(code)."
        case let .timedOut(command, seconds):
            "agent-mail \(command) didn’t finish within \(seconds) seconds."
        case let .decode(what, _):
            "Agent Mail couldn’t read the output of agent-mail \(what)."
        case let .schemaMismatch(found, supported):
            "This version of Agent Mail understands status schema \(supported), but agent-mail reported "
                + (found.map { "schema \($0)" } ?? "no schema") + "."
        }
    }

    public var failureReason: String? {
        switch self {
        case let .cliNotFound(searched):
            "Looked in: " + searched.joined(separator: ", ")
        case let .launchFailed(_, reason):
            reason
        case let .exit(_, code, stderr):
            stderr.isEmpty ? "Exit code \(code)." : stderr.trimmingCharacters(in: .whitespacesAndNewlines)
        case .timedOut:
            "The store may be locked by another agent-mail command."
        case let .decode(_, detail):
            detail
        case .schemaMismatch:
            "The command-line tool and the app are out of step."
        }
    }

    public var recoverySuggestion: String? {
        switch self {
        case .cliNotFound, .launchFailed:
            "Install it with `just install` in the agent-mail repo, or choose its path in Settings › General."
        case .exit, .timedOut:
            "Try again. If it keeps failing, run the same command in Terminal to see more."
        case .decode:
            "Make sure the agent-mail command is up to date."
        case .schemaMismatch:
            "Update the agent-mail repo and the app to the same version."
        }
    }

    private static func firstLine(_ s: String) -> String? {
        let line = s.split(whereSeparator: \.isNewline).first.map(String.init)?
            .trimmingCharacters(in: .whitespaces)
        guard let line, !line.isEmpty else { return nil }
        return line.hasPrefix("agent-mail: ") ? String(line.dropFirst("agent-mail: ".count)) : line
    }
}
