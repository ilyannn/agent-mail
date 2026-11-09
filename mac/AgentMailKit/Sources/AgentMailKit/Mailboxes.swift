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

/// A row under Participants: the user (`status.me`) or a repo.
public struct AgentInfo: Hashable, Sendable {
    public var name: String
    /// The user's own row, whose mailbox is every thread the user takes part in.
    public var isMe: Bool
    public var onDuty: Bool
    /// On call: `agent-mail serve` opens a listener tab for it when mail
    /// waits and nobody is on duty (MailStatus.onCallRepos).
    public var onCall: Bool
    /// Open threads waiting on this participant: its sidebar badge.
    public var waiting: Int
    public var sessions: [DutyRecord]

    public init(name: String, isMe: Bool = false, onDuty: Bool = false, onCall: Bool = false, waiting: Int = 0,
                sessions: [DutyRecord] = []) {
        self.name = name
        self.isMe = isMe
        self.onDuty = onDuty
        self.onCall = onCall
        self.waiting = waiting
        self.sessions = sessions
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

    /// The sidebar row's tooltip: who it is (on duty, and in which sessions,
    /// or on call, for a repo), then what the badge counts.
    public var tooltip: String {
        var who: String
        if isMe {
            who = "you (\(name)): every thread you take part in"
        } else {
            who = "\(name): " + (onDuty ? "on duty" : onCall ? "on call — a tab opens when mail comes" : "nobody on duty")
            let live = sessions.filter(\.onDuty).map { "\($0.session) (\($0.state))" }
            if !live.isEmpty { who += " — " + live.joined(separator: ", ") }
        }
        return who + "\n" + waitingText
    }

    /// The sidebar row's VoiceOver label; it names the count only when the
    /// badge shows one.
    public var accessibilityLabel: String {
        var parts = isMe ? ["\(name) (you)"] : [name, onDuty ? "on duty" : onCall ? "on call" : "not on duty"]
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
    /// The sidebar's Participants: the user (`me`) first, then every repo on
    /// duty, on call or in a thread, by name.
    public let participants: [AgentInfo]
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

        let duty = status?.duty ?? []
        let onCall = status?.onCallRepos ?? []
        let repos = Set(duty.map(\.repo)).union(threads.flatMap(\.participants))
            .union((status?.oncall ?? []).map(\.repo)).subtracting([me])
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        participants = ([me] + repos).map { name in
            let sessions = duty.filter { $0.repo == name }
            return AgentInfo(name: name,
                             isMe: name == me,
                             onDuty: sessions.contains(where: \.onDuty),
                             onCall: onCall.contains(name),
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
