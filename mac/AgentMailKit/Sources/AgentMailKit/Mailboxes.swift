// The sidebar's mailboxes and participants, derived from one status. Pure:
// the CLI already decided who waits on whom and who is listening; this only
// filters, orders and words the counts.
import Foundation

public enum Mailbox: Hashable, Sendable, Codable {
    /// Open threads waiting on the user.
    case inbox
    /// Open threads nobody is listening for.
    case undelivered
    /// Every thread, in the chosen ThreadOrder.
    case allMail
    /// Every thread a participant takes part in, in the chosen ThreadOrder:
    /// a repo, or the user's own address (`status.me`) for the user's row.
    case agent(String)

    public var title: String {
        switch self {
        case .inbox: "Inbox"
        case .undelivered: "Undelivered"
        case .allMail: "All Mail"
        case let .agent(name): name
        }
    }
}

/// How All Mail and a participant's mailbox are sorted. The Inbox is newest
/// first and Undelivered keeps the CLI's order, whatever this says.
public enum ThreadOrder: String, CaseIterable, Codable, Sendable {
    /// Latest activity first, open and closed threads mixed.
    case newestFirst
    /// Open threads first, then closed ones, each newest first.
    case openFirst

    public var title: String {
        switch self {
        case .newestFirst: "Newest First"
        case .openFirst: "Open First"
        }
    }
}

/// Which of a participant's threads its mailbox shows: the scope bar above
/// the thread list, and View › Participant Filter. Applies to participant
/// mailboxes only. Sent and Received split All between them; Waiting counts
/// what the participant's sidebar badge counts.
public enum ParticipantFilter: String, CaseIterable, Codable, Sendable {
    /// Every thread the participant takes part in.
    case all
    /// Threads the participant started.
    case sent
    /// Threads someone else started that the participant takes part in.
    case received
    /// Open threads waiting on the participant.
    case waiting

    public var title: String {
        switch self {
        case .all: "All"
        case .sent: "Sent"
        case .received: "Received"
        case .waiting: "Waiting"
        }
    }

    /// Whether `thread`, one that `name` takes part in, passes the filter.
    public func includes(_ thread: ThreadSummary, for name: String) -> Bool {
        switch self {
        case .all: true
        case .sent: thread.from == name
        case .received: thread.from != name
        case .waiting: thread.isWaiting(on: name)
        }
    }

    /// What the filter shows, naming the participant ("you" for the user's
    /// own row): the scope bar's tooltips.
    public func explanation(for name: String, isMe: Bool) -> String {
        let who = isMe ? "you" : name
        return switch self {
        case .all: "Every thread \(who) take\(isMe ? "" : "s") part in"
        case .sent: "Threads \(who) started"
        case .received: "Threads others started that \(who) take\(isMe ? "" : "s") part in"
        case .waiting: "Open threads waiting on \(who)"
        }
    }

    /// The thread list's text when the filter leaves nothing.
    public func emptyText(for name: String, isMe: Bool) -> String {
        switch self {
        case .all: isMe ? "No threads yet" : "No threads with \(name)"
        case .sent: isMe ? "You haven’t started a thread" : "\(name) hasn’t started a thread"
        case .received: "No threads others started with \(isMe ? "you" : name)"
        case .waiting: "Nothing waiting on \(isMe ? "you" : name)"
        }
    }
}

/// Something wrong with an agent that the user should know of: shown as a
/// warning on its sidebar row and at the top of its mailbox.
public struct AgentNotice: Hashable, Sendable {
    public enum Kind: Hashable, Sendable {
        /// Its folder is gone: serve can't start a session there.
        case folderGone
        /// On call in a folder Claude Code hasn't trusted, where serve can't
        /// start a session either.
        case untrusted
        /// Serve can't fast-forward the agent's main checkout to what its
        /// sessions pushed (uncommitted changes, someone working there,
        /// diverged history).
        case cannotLand
        /// A finished session of serve's is kept because its worktree
        /// branch isn't merged.
        case unlanded
    }

    public var kind: Kind
    public var text: String

    public init(_ kind: Kind, _ text: String) {
        self.kind = kind
        self.text = text
    }
}

/// A row under Participants: the user (`status.me`), an agent in the
/// directory, or a name that only appears in mail (from before the
/// directory, an entry removed since, `postmaster`).
public struct AgentInfo: Hashable, Sendable {
    public var name: String
    /// The user's own row, whose mailbox is every thread the user takes part in.
    public var isMe: Bool
    public var onDuty: Bool
    /// Its directory entry; nil when the name isn't in the directory, or the
    /// CLI predates the directory.
    public var entry: DirectoryEntry?
    /// Whether the status had a directory at all (a CLI that predates it
    /// has none), so a name without an entry is known to be outside it.
    public var directoryKnown: Bool
    /// What serve is doing about it, while it is on call.
    public var serve: OnCallRepo?
    /// Serve delivers its mail now: it runs and doesn't back off from it
    /// (MailStatus.onCallRepos).
    public var delivering: Bool
    /// Whether `agent-mail serve` runs, which on-call agents' mail needs.
    public var serveRunning: Bool
    /// Open threads waiting on this participant: its sidebar badge.
    public var waiting: Int
    public var sessions: [DutyRecord]

    public init(name: String, isMe: Bool = false, onDuty: Bool = false, entry: DirectoryEntry? = nil,
                directoryKnown: Bool = false, serve: OnCallRepo? = nil, delivering: Bool = false,
                serveRunning: Bool = false, waiting: Int = 0, sessions: [DutyRecord] = []) {
        self.name = name
        self.isMe = isMe
        self.onDuty = onDuty
        self.entry = entry
        self.directoryKnown = directoryKnown
        self.serve = serve
        self.delivering = delivering
        self.serveRunning = serveRunning
        self.waiting = waiting
        self.sessions = sessions
    }

    /// On call: its directory entry says so, or, from a CLI that predates
    /// the directory, `agent-mail oncall` lists it.
    public var onCall: Bool { entry?.onCall ?? (serve != nil) }

    public var inDirectory: Bool { entry != nil }

    /// What it owns, from the directory (a borrowed summary says where from).
    public var summary: String? { entry?.shownSummary }

    /// What is wrong with it, for its row's warning and its mailbox: a
    /// folder that is gone (`agent-mail agents` marks it), or one on call
    /// that Claude Code hasn't trusted, where serve can't start sessions;
    /// a main checkout serve can't bring up to date with what its sessions
    /// pushed, and finished sessions kept for their unlanded commits.
    public var notices: [AgentNotice] {
        guard !isMe else { return [] }
        var out = [AgentNotice]()
        if let entry {
            let dir = entry.dir ?? "its folder"
            if !entry.dirExists {
                out.append(AgentNotice(.folderGone, "Its folder \(dir) is gone, so serve can’t start a session there: "
                    + "`agent-mail agents move \(name) DIR` gives it the new one."))
            } else if entry.onCall, entry.trusted == false {
                out.append(AgentNotice(.untrusted, "Claude Code hasn’t trusted \(dir) yet, so serve can’t start a session "
                    + "there: run `claude` in it once and accept the question."))
            }
        }
        if let why = serve?.cannotLand {
            out.append(AgentNotice(.cannotLand, "Serve can’t bring the main checkout up to date with what its sessions "
                + "pushed: \(why)."))
        }
        for u in serve?.unlanded ?? [] {
            out.append(AgentNotice(.unlanded, "Unlanded work of \(u.text); serve keeps the session until it is merged."))
        }
        return out
    }

    /// The badge spelled out: "2 threads waiting on infra", "1 thread
    /// waiting on you", "Nothing waiting on infra".
    public var waitingText: String {
        let who = isMe ? "you" : name
        return switch waiting {
        case 0: "Nothing waiting on \(who)"
        case 1: "1 thread waiting on \(who)"
        default: "\(waiting) threads waiting on \(who)"
        }
    }

    /// Who picks its mail up: on duty (in which sessions), on call (and
    /// whether serve delivers), off call, or outside the directory.
    public var stateText: String {
        if onDuty {
            let live = sessions.filter(\.onDuty).map { "\($0.session) (\($0.state))" }
            return "on duty" + (live.isEmpty ? "" : " — " + live.joined(separator: ", "))
        }
        if onCall {
            if serve?.isBackingOff == true {
                return "on call, but serve backs off from it" + (serve?.reason.map { ": \($0)" } ?? "")
            }
            return serveRunning ? "on call — serve starts a session for each thread"
                : "on call, but serve isn’t running"
        }
        if inDirectory { return "off call — its mail waits until a session starts in its folder" }
        if name == "postmaster" { return "serve’s postmaster; mail to it goes to the agent agent-mail" }
        return directoryKnown ? "not in the agent directory" : "nobody on duty"
    }

    /// The sidebar row's tooltip: who it is and who picks its mail up, what
    /// it owns, what is wrong with it, then what the badge counts.
    public var tooltip: String {
        var lines = [isMe ? "you (\(name)): every thread you take part in" : "\(name): \(stateText)"]
        if let summary { lines.append(summary) }
        lines += notices.map(\.text)
        lines.append(waitingText)
        return lines.joined(separator: "\n")
    }

    /// The sidebar row's VoiceOver label; it names the count only when the
    /// badge shows one.
    public var accessibilityLabel: String {
        var parts = isMe ? ["\(name) (you)"]
            : [name, onDuty ? "on duty" : onCall ? "on call" : inDirectory ? "off call" : "not on duty"]
        switch notices.count {
        case 0: break
        case 1: parts.append("1 warning")
        case let n: parts.append("\(n) warnings")
        }
        if waiting > 0 { parts.append(waitingText) }
        return parts.joined(separator: ", ")
    }
}

public struct MailboxIndex: Sendable, Equatable {
    public let me: String
    public let inbox: [ThreadSummary]
    public let undelivered: [ThreadSummary]
    /// Every thread, newest first (ThreadOrder.newestFirst).
    public let allMail: [ThreadSummary]
    /// The sidebar's Participants: the user (`me`) first, then, by name,
    /// every agent in the directory and every other name on duty, on call
    /// or in a thread.
    public let participants: [AgentInfo]
    /// The agent directory, by name; nil from a CLI that predates it.
    public let directory: [DirectoryEntry]?
    private let allMailOpenFirst: [ThreadSummary]
    private let byID: [String: ThreadSummary]

    public static let empty = MailboxIndex(status: nil)

    public init(status: MailStatus?) {
        let me = status?.me ?? "user"
        let threads = status?.threads ?? []
        self.me = me
        let byID = Dictionary(threads.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        self.byID = byID
        let newestFirst = threads.sorted(by: Self.newerFirst)
        inbox = newestFirst.filter { $0.isWaiting(on: me) }
        undelivered = (status?.undelivered ?? []).compactMap { byID[$0] }
        allMail = newestFirst
        allMailOpenFirst = newestFirst.filter(\.isOpen) + newestFirst.filter { !$0.isOpen }
        directory = status?.agents

        let duty = status?.duty ?? []
        let oncall = status?.oncall ?? []
        let delivering = status?.onCallRepos ?? []
        let entries = Dictionary((directory ?? []).map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        let serveViews = Dictionary(oncall.map { ($0.repo, $0) }, uniquingKeysWith: { a, _ in a })
        let repos = Set(entries.keys).union(duty.map(\.repo)).union(threads.flatMap(\.participants))
            .union(oncall.map(\.repo)).subtracting([me])
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        participants = ([me] + repos).map { name in
            let sessions = duty.filter { $0.repo == name }
            return AgentInfo(name: name,
                             isMe: name == me,
                             onDuty: sessions.contains(where: \.onDuty),
                             entry: name == me ? nil : entries[name],
                             directoryKnown: status?.agents != nil,
                             serve: name == me ? nil : serveViews[name],
                             delivering: delivering.contains(name),
                             serveRunning: status?.serve?.alive == true,
                             waiting: threads.filter { $0.isWaiting(on: name) }.count,
                             sessions: sessions)
        }
    }

    /// The repos alone, without the user: the recipients New Message offers
    /// and the menu bar's On Duty list.
    public var agents: [AgentInfo] { participants.filter { !$0.isMe } }

    public func participant(_ name: String) -> AgentInfo? { participants.first { $0.name == name } }

    public func thread(_ id: String) -> ThreadSummary? { byID[id] }

    /// A mailbox's threads; `order` sorts All Mail and participant mailboxes
    /// only, and `filter` applies to participant mailboxes only. The user's
    /// own row is `.agent(me)`: every thread with `me` in it.
    public func threads(in mailbox: Mailbox, order: ThreadOrder = .newestFirst,
                        filter: ParticipantFilter = .all) -> [ThreadSummary] {
        switch mailbox {
        case .inbox: inbox
        case .undelivered: undelivered
        case .allMail: everything(order)
        case let .agent(name):
            everything(order).filter { $0.participants.contains(name) && filter.includes($0, for: name) }
        }
    }

    /// Whether the new-thread draft's row shows in `mailbox`: wherever the
    /// thread it starts will be once sent. So All Mail, the user's own
    /// mailbox, and the recipient's once To names one, as `filter` allows
    /// (the user's own is Sent, the recipient's Received and Waiting); never
    /// the Inbox (it will wait on the recipient) or Undelivered.
    public func showsDraft(_ draft: NewThreadDraft, in mailbox: Mailbox, filter: ParticipantFilter = .all) -> Bool {
        guard case let .agent(name) = mailbox else { return mailbox == .allMail }
        let to = Formatting.isValidName(draft.recipient) && draft.recipient != me ? draft.recipient : nil
        let thread = ThreadSummary(id: "", subject: draft.rowTitle, from: me, owner: to,
                                   participants: [me] + (to.map { [$0] } ?? []))
        return thread.participants.contains(name) && filter.includes(thread, for: name)
    }

    /// How many threads `filter` leaves in participant `name`'s mailbox.
    /// `.waiting` equals the participant's sidebar badge.
    public func count(_ filter: ParticipantFilter, for name: String) -> Int {
        allMail.reduce(0) { n, t in n + (t.participants.contains(name) && filter.includes(t, for: name) ? 1 : 0) }
    }

    /// The threads that forward thread `id` (`agent-mail forward`), in the
    /// order they were started: the original's header links to each.
    public func forwards(of id: String) -> [ThreadSummary] {
        allMail.filter { $0.forwardedFrom == id }.sorted { ($0.number ?? 0) < ($1.number ?? 0) }
    }

    private func everything(_ order: ThreadOrder) -> [ThreadSummary] {
        switch order {
        case .newestFirst: allMail
        case .openFirst: allMailOpenFirst
        }
    }

    /// The count shown next to a mailbox, 0 for none. The units differ on
    /// purpose: the Inbox counts unread messages addressed to the user (the
    /// menu bar's number), Undelivered counts threads, and a participant
    /// counts the open threads waiting on it. So the user's row shows as many
    /// threads as the Inbox holds, while the Inbox badge counts the messages
    /// in them that came to the user.
    public func badge(for mailbox: Mailbox) -> Int {
        switch mailbox {
        case .inbox: unreadInInbox
        case .undelivered: undelivered.count
        case .allMail: 0
        case let .agent(name): participant(name)?.waiting ?? 0
        }
    }

    /// Unread messages addressed to the user in threads waiting on the user:
    /// the Inbox badge and the menu bar count. What agents sent each other in
    /// those threads doesn't count, though it is unread too (the thread's
    /// unread dot).
    public var unreadInInbox: Int { inbox.reduce(0) { $0 + $1.unreadToMe } }

    public var onDuty: [AgentInfo] { agents.filter(\.onDuty) }

    /// The mailbox that best shows a thread: the Inbox when it waits on the
    /// user, Undelivered when nobody listens, else All Mail.
    public func preferredMailbox(for id: String) -> Mailbox {
        guard let t = byID[id] else { return .allMail }
        if t.isWaiting(on: me) { return .inbox }
        if t.isUndelivered { return .undelivered }
        return .allMail
    }

    static func newerFirst(_ a: ThreadSummary, _ b: ThreadSummary) -> Bool {
        let da = a.updated ?? a.created ?? .distantPast, db = b.updated ?? b.created ?? .distantPast
        if da != db { return da > db }
        return (a.number ?? 0) > (b.number ?? 0)
    }
}
