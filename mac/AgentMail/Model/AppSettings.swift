// User defaults behind typed properties. Every key can also be given as a
// launch argument (`-cliPath /path`, `-skipFirstRun YES`), which is how tests
// and `just run` point the app at a stub CLI or a fixture store.
import AgentMailKit
import AppKit

enum DockIconPolicy: String, CaseIterable {
    case always
    case whileWindowOpen
    case never

    var title: String {
        switch self {
        case .always: "Always"
        case .whileWindowOpen: "While a Window Is Open"
        case .never: "Never"
        }
    }
}

final class AppSettings {
    static let shared = AppSettings()

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        defaults.register(defaults: [
            Key.dockPolicy: DockIconPolicy.whileWindowOpen.rawValue,
            Key.mainWindowWasOpen: true,
            Key.notifyAgentTraffic: false,
            Key.textZoom: TextZoom.actualSize,
        ])
    }

    private enum Key {
        static let cliPath = "cliPath"
        static let storePath = "storePath"
        static let dockPolicy = "dockPolicy"
        static let notifyAgentTraffic = "notifyAgentTraffic"
        static let mainWindowWasOpen = "mainWindowWasOpen"
        static let firstRunDone = "firstRunDone"
        static let skipFirstRun = "skipFirstRun"
        static let settingsPane = "settingsPane"
        static let mailbox = "mailbox"
        static let textZoom = "textZoom"
    }

    /// Path of the agent-mail executable; empty means "find it".
    var cliPath: String? {
        get { defaults.string(forKey: Key.cliPath).flatMap { $0.isEmpty ? nil : $0 } }
        set { defaults.set(newValue, forKey: Key.cliPath) }
    }

    /// The store, when AGENT_MAIL_DIR isn't set in the app's environment.
    var storePath: String? {
        get { defaults.string(forKey: Key.storePath).flatMap { $0.isEmpty ? nil : $0 } }
        set { defaults.set(newValue, forKey: Key.storePath) }
    }

    var dockPolicy: DockIconPolicy {
        get { DockIconPolicy(rawValue: defaults.string(forKey: Key.dockPolicy) ?? "") ?? .whileWindowOpen }
        set { defaults.set(newValue.rawValue, forKey: Key.dockPolicy) }
    }

    /// Notify about mail between agents, not only mail to the user.
    var notifyAgentTraffic: Bool {
        get { defaults.bool(forKey: Key.notifyAgentTraffic) }
        set { defaults.set(newValue, forKey: Key.notifyAgentTraffic) }
    }

    /// Reopen the main window at launch (it was open when the app quit).
    var mainWindowWasOpen: Bool {
        get { defaults.bool(forKey: Key.mainWindowWasOpen) }
        set { defaults.set(newValue, forKey: Key.mainWindowWasOpen) }
    }

    var firstRunDone: Bool {
        get { defaults.bool(forKey: Key.firstRunDone) || defaults.bool(forKey: Key.skipFirstRun) }
        set { defaults.set(newValue, forKey: Key.firstRunDone) }
    }

    /// The thread view's text size as a page zoom factor (View menu, pinch).
    var textZoom: Double {
        get { TextZoom.clamp(defaults.double(forKey: Key.textZoom)) }
        set { defaults.set(TextZoom.clamp(newValue), forKey: Key.textZoom) }
    }

    var settingsPane: Int {
        get { defaults.integer(forKey: Key.settingsPane) }
        set { defaults.set(newValue, forKey: Key.settingsPane) }
    }

    var mailbox: Mailbox {
        get {
            let raw = defaults.string(forKey: Key.mailbox) ?? ""
            switch raw {
            case "undelivered": return .undelivered
            case "allMail": return .allMail
            case _ where raw.hasPrefix("agent:"): return .agent(String(raw.dropFirst(6)))
            default: return .inbox
            }
        }
        set {
            let raw = switch newValue {
            case .inbox: "inbox"
            case .undelivered: "undelivered"
            case .allMail: "allMail"
            case let .agent(name): "agent:\(name)"
            }
            defaults.set(raw, forKey: Key.mailbox)
        }
    }
}

/// Where the store is, and why: the CLI is always run with AGENT_MAIL_DIR set
/// to this, so the watcher and the CLI never disagree.
struct StoreLocation {
    enum Source: String {
        case environment = "AGENT_MAIL_DIR"
        case settings = "Settings"
        case standard = "default"
    }

    let url: URL
    let source: Source

    static func resolve(environment env: [String: String] = ProcessInfo.processInfo.environment,
                        settings: AppSettings) -> StoreLocation {
        if let dir = env["AGENT_MAIL_DIR"], !dir.isEmpty {
            return StoreLocation(url: URL(filePath: (dir as NSString).expandingTildeInPath), source: .environment)
        }
        if let path = settings.storePath {
            return StoreLocation(url: URL(filePath: (path as NSString).expandingTildeInPath), source: .settings)
        }
        let dataHome = env["XDG_DATA_HOME"].flatMap { $0.isEmpty ? nil : URL(filePath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appending(path: ".local/share")
        return StoreLocation(url: dataHome.appending(path: "agent-mail"), source: .standard)
    }
}
