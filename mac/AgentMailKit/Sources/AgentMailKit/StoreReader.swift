// Read-only peeks into the store for notifications. `status --json` lists
// threads without their message files, and `read` marks a thread seen, so
// the only side-effect-free way to learn which message files are new (and
// what they say) is to list <store>/threads/<id>/msgs/ and read the new ones.
// Nothing here writes.
import Foundation

public struct StoredMessage: Sendable, Equatable {
    public var threadID: String
    public var file: String
    public var from: String?
    public var to: String?
    public var date: Date?
    public var status: String?
    public var body: String

    public init(threadID: String, file: String, from: String?, to: String?, date: Date?, status: String?, body: String) {
        self.threadID = threadID
        self.file = file
        self.from = from
        self.to = to
        self.date = date
        self.status = status
        self.body = body
    }
}

public struct StoreReader: Sendable {
    public var root: URL

    public init(root: URL) { self.root = root }

    public func threadDirectory(_ id: String) -> URL { root.appending(path: "threads").appending(path: id) }
    public func filesDirectory(_ id: String) -> URL { threadDirectory(id).appending(path: "files") }

    /// Message file names of a thread, sorted (= written order). Temporary
    /// files (`.name.tmp`) are skipped.
    public func messageFiles(thread id: String) -> [String] {
        let dir = threadDirectory(id).appending(path: "msgs")
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { $0.hasSuffix(".md") && !$0.hasPrefix(".") }.sorted()
    }

    public func message(thread id: String, file: String) -> StoredMessage? {
        let url = threadDirectory(id).appending(path: "msgs").appending(path: file)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let (fm, body) = Frontmatter.split(text)
        return StoredMessage(threadID: id, file: file, from: fm["from"], to: fm["to"],
                             date: fm["date"].flatMap(AgentMailJSON.parseDate), status: fm["status"],
                             body: body.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

/// The store's frontmatter: `key: "value"` lines (every scalar quoted with
/// `\\`, `\"` and `\n` escapes, see core/fm-str), between `---` lines. Lists
/// are kept as their raw text; only scalars are needed here.
public enum Frontmatter {
    public static func split(_ text: String) -> (fields: [String: String], body: String) {
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        guard normalized.hasPrefix("---\n") else { return ([:], normalized) }
        let rest = normalized.dropFirst(4)
        guard let end = rest.range(of: "\n---\n") ?? rest.range(of: "\n---", options: .anchored.union(.backwards))
        else { return ([:], normalized) }
        let header = rest[rest.startIndex..<end.lowerBound]
        let body = rest[end.upperBound...]
        var fields: [String: String] = [:]
        for line in header.split(separator: "\n", omittingEmptySubsequences: true) {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[line.startIndex..<colon].trimmingCharacters(in: .whitespaces)
            let raw = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            fields[key] = unquote(raw)
        }
        return (fields, String(body))
    }

    static func unquote(_ raw: String) -> String {
        guard raw.count >= 2, raw.hasPrefix("\""), raw.hasSuffix("\"") else { return raw }
        var out = ""
        var escaping = false
        for ch in raw.dropFirst().dropLast() {
            if escaping {
                switch ch {
                case "n": out.append("\n")
                case "t": out.append("\t")
                default: out.append(ch)
                }
                escaping = false
            } else if ch == "\\" {
                escaping = true
            } else {
                out.append(ch)
            }
        }
        return out
    }
}
