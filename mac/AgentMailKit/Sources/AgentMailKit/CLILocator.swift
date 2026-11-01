// Finding the agent-mail executable from a GUI app, whose PATH is minimal:
// the Settings override, then ~/.local/bin/agent-mail (where `just install`
// links it), then whatever the user's login shell finds.
import Foundation

public struct CLILocator: Sendable {
    public var override: String?
    public var home: URL
    /// The user's login shell, for the last resort.
    public var shell: String
    public var runner: any CommandRunning

    public init(override: String?, home: URL = FileManager.default.homeDirectoryForCurrentUser,
                shell: String = CLILocator.loginShell(), runner: any CommandRunning = ProcessRunner()) {
        self.override = override
        self.home = home
        self.shell = shell
        self.runner = runner
    }

    public var defaultPath: URL { home.appending(path: ".local/bin/agent-mail") }

    public func locate() async throws(AgentMailError) -> URL {
        var searched: [String] = []
        if let o = override?.trimmingCharacters(in: .whitespaces), !o.isEmpty {
            let path = (o as NSString).expandingTildeInPath
            guard FileManager.default.isExecutableFile(atPath: path) else {
                throw .cliNotFound(searched: ["\(path) (from Settings)"])
            }
            return URL(filePath: path)
        }
        searched.append(defaultPath.path)
        if FileManager.default.isExecutableFile(atPath: defaultPath.path) { return defaultPath }

        searched.append("\(shell) -l -c 'command -v agent-mail'")
        if let found = try? await runner.run(URL(filePath: shell), arguments: ["-l", "-c", "command -v agent-mail"],
                                             environment: ProcessInfo.processInfo.environment, directory: home,
                                             input: nil, timeout: .seconds(10)),
           found.status == 0,
           let path = Self.lastAbsolutePath(in: found.stdoutString),
           FileManager.default.isExecutableFile(atPath: path) {
            return URL(filePath: path)
        }
        throw .cliNotFound(searched: searched)
    }

    /// Login shells may print banners; the answer is the last line that is an
    /// absolute path.
    static func lastAbsolutePath(in output: String) -> String? {
        output.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .last { $0.hasPrefix("/") }
    }

    /// $SHELL, else the account's shell from the user database, else zsh.
    public static func loginShell() -> String {
        if let s = ProcessInfo.processInfo.environment["SHELL"], !s.isEmpty { return s }
        if let pw = getpwuid(getuid()), let sh = pw.pointee.pw_shell { return String(cString: sh) }
        return "/bin/zsh"
    }
}
