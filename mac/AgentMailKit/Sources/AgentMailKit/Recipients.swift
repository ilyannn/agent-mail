// Whom mail can go to: the agent directory's names, as To's choices with
// their summaries, and what To says of a typed name before anything is
// sent. The CLI refuses a name that is neither `user`, `postmaster` nor in
// the directory (known!), naming the closest ones (core/closest-names); a
// CLI that predates the directory takes any name.
import Foundation

/// One of To's choices: an agent in the directory.
public struct RecipientChoice: Hashable, Sendable {
    public var name: String
    public var summary: String?
    public var onCall: Bool

    public init(name: String, summary: String? = nil, onCall: Bool = true) {
        self.name = name
        self.summary = summary
        self.onCall = onCall
    }

    /// How many characters of the summary the list shows.
    public static let summaryChars = 60

    /// What To's list shows: "infra — Owns the servers, DNS and deploys.",
    /// the summary cut to fit; picking it puts the name alone in To
    /// (Recipients.name(fromListTitle:)).
    public var listTitle: String {
        guard let summary = summary?.nonBlank else { return name }
        let short = summary.count > Self.summaryChars ? String(summary.prefix(Self.summaryChars - 1)) + "…" : summary
        return "\(name) — \(short)"
    }
}

/// What To says of the name in it.
public enum RecipientCheck: Equatable, Sendable {
    /// Nothing typed yet.
    case empty
    /// Not a name: it has spaces or slashes.
    case invalid
    /// The user's own name.
    case me
    /// A name mail can go to: an agent in the directory (its entry), or,
    /// without an entry, `postmaster` or any name a CLI that predates the
    /// directory takes.
    case known(DirectoryEntry?)
    /// A name the directory doesn't have, which the CLI refuses, with up to
    /// three close names.
    case unknown(String, closest: [String])

    public var allowsSending: Bool {
        if case .known = self { return true }
        return false
    }

    /// The pane's status line for it: what is wrong, or what the agent owns.
    public var message: String {
        switch self {
        case .empty: return ""
        case .invalid: return "A name has no spaces or slashes"
        case .me: return "That’s you"
        case let .known(entry):
            guard let entry else { return "" }
            let summary = entry.summary ?? ""
            guard !entry.onCall else { return summary }
            return summary.isEmpty ? "Off call: nothing delivers its mail until a session starts in its folder"
                : "\(summary) (off call)"
        case let .unknown(name, closest):
            return "No agent named \(name) in the directory: "
                + (closest.isEmpty ? "File › Add Folder… adds one" : "did you mean \(Recipients.orList(closest))?")
        }
    }
}

public enum Recipients {
    /// The name in a To value: the value, or the name of a list title
    /// ("infra — Owns…"); names have no spaces, so the dash can't be one's.
    public static func name(fromListTitle value: String) -> String {
        guard let r = value.range(of: " — ") else { return value }
        return String(value[..<r.lowerBound]).trimmingCharacters(in: .whitespaces)
    }

    /// Up to three of `names` closest to `s`, a name not among them
    /// (core/closest-names): those that contain it, or that it contains
    /// when three characters or longer, first; then those within a few
    /// edits (a third of its length, at least two) that keep something of
    /// the name; case aside, nearest first, then by name.
    public static func closest(to s: String, in names: [String]) -> [String] {
        let l = s.lowercased()
        var seen = Set<String>()
        var scored: [(Int, Int, String)] = []
        for n in names where seen.insert(n).inserted && n != s {
            let m = n.lowercased()
            let d = editDistance(l, m)
            let sub = (!l.isEmpty && m.contains(l)) || (m.count >= 3 && l.contains(m))
            if sub || (d <= max(2, l.count / 3) && d < m.count) { scored.append((sub ? 0 : 1, d, n)) }
        }
        return scored.sorted { ($0.0, $0.1, $0.2) < ($1.0, $1.1, $1.2) }.prefix(3).map(\.2)
    }

    /// Levenshtein distance between `a` and `b`, by character.
    static func editDistance(_ a: String, _ b: String) -> Int {
        let b = Array(b)
        var prev = Array(0...b.count)
        for (i, x) in a.enumerated() {
            var row = [i + 1]
            for (j, y) in b.enumerated() {
                row.append(min(row[j] + 1, prev[j + 1] + 1, prev[j] + (x == y ? 0 : 1)))
            }
            prev = row
        }
        return prev[b.count]
    }

    /// Names in a sentence: `a`, `a or b`, `a, b or c`.
    static func orList(_ names: [String]) -> String {
        guard names.count > 1 else { return names.first ?? "" }
        return names.dropLast().joined(separator: ", ") + " or " + names[names.count - 1]
    }
}

extension MailboxIndex {
    /// To's choices, by name: the directory's agents, without the user;
    /// from a CLI that predates the directory, every participant that
    /// isn't the user, without summaries.
    public var recipientChoices: [RecipientChoice] {
        guard let directory else { return agents.map { RecipientChoice(name: $0.name) } }
        return directory.filter { $0.name != me }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            .map { RecipientChoice(name: $0.name, summary: $0.summary, onCall: $0.onCall) }
    }

    /// What To says of `recipient`, before it is sent to.
    public func check(recipient: String) -> RecipientCheck {
        let name = recipient.trimmingCharacters(in: .whitespaces)
        if name.isEmpty { return .empty }
        if !Formatting.isValidName(name) { return .invalid }
        if name == me { return .me }
        guard let directory else { return .known(nil) }
        if let entry = directory.first(where: { $0.name == name }) { return .known(entry) }
        if name == "postmaster" { return .known(nil) }
        return .unknown(name, closest: Recipients.closest(to: name, in: directory.map(\.name)))
    }

    /// Whether Send may send `draft`: what NewThreadDraft.canSend asks,
    /// and a recipient the CLI takes.
    public func canSend(_ draft: NewThreadDraft) -> Bool {
        draft.canSend(me: me) && check(recipient: draft.recipient).allowsSending
    }
}
