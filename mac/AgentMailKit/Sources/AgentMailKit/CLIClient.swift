// The app's only way into the store: the agent-mail CLI, run as `user` from
// the home directory. Reads decode JSON; writes pass the body on stdin and
// return what the CLI printed. A non-zero exit is an AgentMailError.exit.
// The app never mutates its own state after a write: the CLI changes the
// store and the store watcher picks the change up.
import Foundation

public struct CLIClient: Sendable {
    public var executable: URL
    public var environment: [String: String]
    public var directory: URL
    public var runner: any CommandRunning
    public var timeout: Duration

    public init(executable: URL, environment: [String: String], directory: URL,
                runner: any CommandRunning = ProcessRunner(), timeout: Duration = .seconds(30)) {
        self.executable = executable
        self.environment = environment
        self.directory = directory
        self.runner = runner
        self.timeout = timeout
    }

    /// The CLI's environment: the app's own, acting as `user`, against
    /// `store` (so the CLI and the watcher agree on it), and without the CLI's
    /// own desktop notifications (the app posts its own).
    public static func environment(base: [String: String], store: URL) -> [String: String] {
        var env = base
        env["AGENT_MAIL_AS"] = "user"
        env["AGENT_MAIL_DIR"] = store.path
        env["AGENT_MAIL_NO_NOTIFY"] = "1"
        // Identity is explicit; an agent marker inherited from a terminal
        // that launched the app must not matter.
        env.removeValue(forKey: "CLAUDE_CODE_CHILD_SESSION")
        return env
    }

    public struct Timed<Value: Sendable>: Sendable {
        public var value: Value
        public var duration: Duration
    }

    public func status() async throws(AgentMailError) -> Timed<MailStatus> {
        let r = try await run(["status", "--json"], label: "status")
        return Timed(value: try AgentMailJSON.decodeStatus(r.stdout), duration: r.duration)
    }

    /// The whole thread. Note: the CLI marks it seen for `user`.
    public func thread(_ id: String) async throws(AgentMailError) -> Timed<ThreadDetail> {
        let r = try await run(["read", id, "--json"], label: "read")
        return Timed(value: try AgentMailJSON.decodeThread(r.stdout), duration: r.duration)
    }

    /// `attachments` (absolute file paths) go as `--attach`, which copies
    /// each file into the thread; the CLI refuses, before writing anything,
    /// when one is missing.
    @discardableResult
    public func reply(threadID: String, to recipient: String, body: String,
                      attachments: [URL] = []) async throws(AgentMailError) -> String {
        try await run(["reply", threadID, "--to", recipient] + Self.attachArguments(attachments), input: body,
                      label: "reply").stdoutString
    }

    /// Starts a thread; returns its id.
    @discardableResult
    public func send(to recipient: String, subject: String?, body: String,
                     attachments: [URL] = []) async throws(AgentMailError) -> String {
        var args = ["send", "--to", recipient]
        if let subject, !subject.trimmingCharacters(in: .whitespaces).isEmpty { args += ["--subject", subject] }
        return try await run(args + Self.attachArguments(attachments), input: body, label: "send").stdoutString
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// `--attach PATH` for each file.
    static func attachArguments(_ files: [URL]) -> [String] {
        files.flatMap { ["--attach", $0.path] }
    }

    /// Starts a thread to `recipient` carrying thread `threadID`, or only its
    /// message `message` (a file name, as `read --json` gives it): the note,
    /// then the forwarded messages, their files copied along. Returns the new
    /// thread's id. A blank subject leaves the CLI's "Fwd: <subject>". The
    /// note goes as one argument after `--`, so nothing in it reads as an
    /// option; stdin stays empty (the CLI reads it only when it is ready).
    @discardableResult
    public func forward(threadID: String, message: String? = nil, to recipient: String, subject: String? = nil,
                        note: String = "", attachments: [URL] = []) async throws(AgentMailError) -> String {
        try await run(Self.forwardArguments(threadID: threadID, message: message, to: recipient, subject: subject,
                                            note: note, attachments: attachments), label: "forward").stdoutString
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func forwardArguments(threadID: String, message: String?, to recipient: String, subject: String?,
                                 note: String, attachments: [URL] = []) -> [String] {
        var args = ["forward", threadID, "--to", recipient]
        if let message { args += ["--msg", message] }
        if let subject, !subject.trimmingCharacters(in: .whitespaces).isEmpty { args += ["--subject", subject] }
        args += attachArguments(attachments)
        if !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { args += ["--", note] }
        return args
    }

    /// Closes the thread; an empty text closes it with the CLI's "Closed.".
    @discardableResult
    public func close(threadID: String, text: String = "", attachments: [URL] = []) async throws(AgentMailError) -> String {
        try await run(["close", threadID] + Self.attachArguments(attachments), input: text, label: "close").stdoutString
    }

    /// Adds folder `directory` (an absolute path) to the agent directory, on
    /// call, named as the CLI names it (`agents add DIR`); returns the name.
    /// The CLI refuses `/`, the home folder, a folder already in the
    /// directory and a name already taken, saying why (AgentMailError.exit).
    @discardableResult
    public func addAgent(directory: String) async throws(AgentMailError) -> String? {
        let out = try await run(["agents", "add", directory], label: "agents add").stdoutString
        return Self.addedAgentName(out)
    }

    /// The name `agents add` gave the folder, from its first line: `infra is
    /// in the agent directory, on call (/x/infra)`.
    static func addedAgentName(_ output: String) -> String? {
        let first = output.split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
        guard let r = first.range(of: " is in the agent directory") else { return nil }
        let name = String(first[..<r.lowerBound])
        return Formatting.isValidName(name) ? name : nil
    }

    /// Puts agent `name` on call or takes it off (`oncall add NAME`, `oncall
    /// remove NAME`).
    @discardableResult
    public func setOnCall(_ name: String, _ onCall: Bool) async throws(AgentMailError) -> String {
        try await run(["oncall", onCall ? "add" : "remove", name], label: onCall ? "oncall add" : "oncall remove")
            .stdoutString
    }

    func run(_ args: [String], input: String? = nil, label: String) async throws(AgentMailError) -> ProcessResult {
        let r: ProcessResult
        do {
            r = try await runner.run(executable, arguments: args, environment: environment, directory: directory,
                                     input: input.map { Data($0.utf8) } ?? Data(), timeout: timeout)
        } catch ProcessRunError.timedOut {
            throw .timedOut(command: label, seconds: Int(timeout.components.seconds))
        } catch let ProcessRunError.launch(reason) {
            throw .launchFailed(path: executable.path, reason: reason)
        } catch {
            throw .launchFailed(path: executable.path, reason: error.localizedDescription)
        }
        guard r.status == 0 else {
            throw .exit(command: label, code: r.status, stderr: r.stderrString)
        }
        return r
    }
}
