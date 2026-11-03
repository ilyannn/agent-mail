// A new thread being written in the main window: File › New Message, or
// Forward on a closed thread (`agent-mail forward`), shown as an empty thread
// with its reply box. Pure values: what may be sent, what is worth asking
// about before it is thrown away, and the words of its row and header.
import Foundation

/// What a forward carries: a whole thread, or one of its messages.
public struct ForwardSource: Sendable, Hashable {
    public var threadID: String
    /// The one message forwarded (its file name, as `read --json` gives
    /// it); nil forwards every message.
    public var message: String?

    public init(threadID: String, message: String? = nil) {
        self.threadID = threadID
        self.message = message
    }
}

/// The new thread; the app holds at most one.
public struct NewThreadDraft: Sendable, Equatable, Identifiable {
    /// Tells one draft from the next, so a view knows when to load its fields.
    public let id: UUID
    public var to: String
    /// Optional: without one the CLI takes the body's first line (a forward's
    /// is "Fwd: <subject>").
    public var subject: String
    public var body: String
    public var forward: ForwardSource?
    /// What the app filled in itself; leaving it as it is isn't typing.
    public private(set) var prefilledTo: String
    public let prefilledSubject: String

    public init(to: String = "", subject: String = "", body: String = "", forward: ForwardSource? = nil,
                id: UUID = UUID()) {
        self.id = id
        self.to = to
        self.subject = subject
        self.body = body
        self.forward = forward
        prefilledTo = to
        prefilledSubject = subject
    }

    /// A forward of `source`, whose subject was `subject`, to nobody yet.
    public static func forwarding(_ source: ForwardSource, subject: String) -> NewThreadDraft {
        NewThreadDraft(subject: Forwarding.subject(subject), forward: source)
    }

    public var recipient: String { to.trimmingCharacters(in: .whitespaces) }

    /// Something the user typed (not what the app filled in), which Discard
    /// and starting another draft ask about before throwing it away.
    public var hasTypedContent: Bool {
        func typed(_ value: String, over prefill: String) -> Bool {
            let v = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return !v.isEmpty && v != prefill.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return typed(body, over: "") || typed(subject, over: prefilledSubject) || typed(to, over: prefilledTo)
    }

    /// A recipient the CLI accepts and, unless it is a forward (whose note is
    /// optional), a body.
    public func canSend(me: String) -> Bool {
        Formatting.isValidRecipient(recipient, me: me)
            && (forward != nil || !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    /// New Message again while this draft exists: fills To when it is still
    /// empty, as if the app had put it there from the start.
    public mutating func fillRecipient(_ name: String) {
        guard recipient.isEmpty, !name.isEmpty else { return }
        to = name
        prefilledTo = name
    }

    /// The draft row's first line: the subject, else what it is.
    public var rowTitle: String {
        let s = subject.trimmingCharacters(in: .whitespaces)
        if !s.isEmpty { return s }
        return forward == nil ? "New Message" : "Forward"
    }

    /// The draft row's second line: "to infra", or "Draft" until it has a
    /// recipient.
    public var rowDetail: String {
        Formatting.isValidName(recipient) ? "to \(recipient)" : "Draft"
    }
}

public enum Forwarding {
    /// A forward's subject: the original's with "Fwd: " in front, once
    /// (core/forward-subject).
    public static func subject(_ original: String) -> String {
        original.hasPrefix("Fwd: ") ? original : "Fwd: \(original)"
    }

    /// The draft header's line saying what goes along: "Forwarding #12, all
    /// 3 messages and their attachments, after your note", or "Forwarding
    /// infra’s message of 3 Nov, 10:02 from #12". `thread` is the source once
    /// loaded; until then `summary` names it and counts its messages.
    public static func description(of source: ForwardSource, thread: ThreadDetail?, summary: ThreadSummary?,
                                   me: String, formatDate: (Date) -> String) -> String {
        let number = thread?.number ?? summary?.number
        let subject = thread?.subject ?? summary?.subject ?? ""
        let name = number.map { "#\($0)" } ?? (subject.isEmpty ? "the thread" : "“\(subject)”")

        guard let file = source.message else {
            let count = thread?.msgs.count ?? summary?.count ?? 0
            let attached = thread?.msgs.contains { !$0.attachments.isEmpty } ?? false
            let what = switch count {
            case 0: ""
            case 1: ", its one message" + (attached ? " and its attachments" : "")
            default: ", all \(count) messages" + (attached ? " and their attachments" : "")
            }
            return "Forwarding \(name)\(what), after your note"
        }
        guard let m = thread?.msgs.first(where: { $0.file == file }) else {
            return "Forwarding a message from \(name)"
        }
        let whose = m.from == me ? "your" : "\(m.from ?? "someone")’s"
        var text = "Forwarding \(whose) message"
        if let date = m.date { text += " of \(formatDate(date))" }
        text += " from \(name)"
        switch m.attachments.count {
        case 0: break
        case 1: text += ", with its attachment"
        case let n: text += ", with its \(n) attachments"
        }
        return text
    }
}
