// What is new since the last status run: the message files that weren't
// there before. The seen set is persisted (so a relaunch doesn't re-announce
// everything); with no saved set, the first run only records what exists and
// announces nothing. Only threads whose content fingerprint changed are
// listed again, so a refresh costs one directory listing per changed thread.
import Foundation

public struct MessageRef: Hashable, Sendable, Codable {
    public var threadID: String
    public var file: String

    public init(threadID: String, file: String) {
        self.threadID = threadID
        self.file = file
    }

    var key: String { "\(threadID)/\(file)" }
}

public struct NewMessageTracker: Sendable {
    /// `thread/file` keys of every message already accounted for.
    public private(set) var seen: Set<String>
    /// False until there is a baseline (a saved seen set, or a first run).
    public private(set) var primed: Bool
    /// Thread fingerprints from the last update; empty after launch, so the
    /// first update lists every thread.
    private var fingerprints: [String: String] = [:]

    /// `saved` is the persisted seen set, nil when there is none yet.
    public init(saved: Set<String>?) {
        seen = saved ?? []
        primed = saved != nil
    }

    /// The message files that appeared since the previous update, in thread
    /// then file order. Returns nothing while priming.
    public mutating func update(threads: [ThreadSummary], listFiles: (String) -> [String]) -> [MessageRef] {
        var fresh: [MessageRef] = []
        var nextFingerprints: [String: String] = [:]
        let live = Set(threads.map(\.id))
        for t in threads {
            nextFingerprints[t.id] = t.contentFingerprint
            guard fingerprints[t.id] != t.contentFingerprint else { continue }
            for file in listFiles(t.id) {
                let ref = MessageRef(threadID: t.id, file: file)
                if seen.insert(ref.key).inserted { fresh.append(ref) }
            }
        }
        // Forget threads that are gone, so the set doesn't grow without end.
        seen = seen.filter { key in key.split(separator: "/", maxSplits: 1).first.map { live.contains(String($0)) } ?? false }
        fingerprints = nextFingerprints
        if !primed {
            primed = true
            return []
        }
        return fresh
    }
}

/// Which new messages notify: mail to the user always; agent-to-agent mail
/// only when asked for; never the user's own messages.
public enum NotificationPolicy {
    public static func shouldNotify(_ m: StoredMessage, me: String, agentTraffic: Bool) -> Bool {
        if m.from == me { return false }
        if m.to == me { return true }
        return agentTraffic
    }
}

/// The persisted seen set: a JSON array in Application Support, one file
/// per store path so a test store never touches the real one's set.
public struct SeenSetFile: Sendable {
    public var url: URL

    public init(directory: URL, store: URL) {
        let name = store.standardizedFileURL.path.unicodeScalars
            .map { CharacterSet.alphanumerics.contains($0) ? String($0) : "_" }.joined()
        url = directory.appending(path: "seen-\(name.suffix(120)).json")
    }

    public func load() -> Set<String>? {
        guard let data = try? Data(contentsOf: url),
              let keys = try? JSONDecoder().decode([String].self, from: data) else { return nil }
        return Set(keys)
    }

    public func save(_ keys: Set<String>) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(keys.sorted()).write(to: url, options: .atomic)
    }
}
