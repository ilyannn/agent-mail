// The sidebar's mailboxes and agents, derived from one status. Pure: the
// CLI already decided who waits on whom and who is listening; this only
// filters and orders.
import Foundation

public enum Mailbox: Hashable, Sendable, Codable {
    /// Open threads waiting on the user.
    case inbox
    /// Open threads nobody is listening for.
    case undelivered
    /// Every thread, in the chosen ThreadOrder.
    case allMail
    /// Every thread a repo takes part in, in the chosen ThreadOrder.
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

/// How All Mail and an agent's mailbox are sorted. The Inbox is newest
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

public struct AgentInfo: Hashable, Sendable {
    public var name: String
    public var onDuty: Bool
    /// Messages this agent has not read yet, over all its threads.
    public var unread: Int
    /// Open threads waiting on this agent.
    public var waiting: Int
    public var sessions: [DutyRecord]
}

public struct MailboxIndex: Sendable, Equatable {
    public let me: String
    public let inbox: [ThreadSummary]
    public let undelivered: [ThreadSummary]
    /// Every thread, newest first (ThreadOrder.newestFirst).
    public let allMail: [ThreadSummary]
    public let agents: [AgentInfo]
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
        let names = Set(duty.map(\.repo)).union(threads.flatMap(\.participants)).subtracting([me])
        agents = names.sorted { $0.localizedStandardCompare($1) == .orderedAscending }.map { name in
            let sessions = duty.filter { $0.repo == name }
            return AgentInfo(name: name,
                             onDuty: sessions.contains(where: \.onDuty),
                             unread: threads.reduce(0) { $0 + ($1.unreadBy[name] ?? 0) },
                             waiting: threads.filter { $0.isWaiting(on: name) }.count,
                             sessions: sessions)
        }
    }

    public func thread(_ id: String) -> ThreadSummary? { byID[id] }

    /// A mailbox's threads; `order` sorts All Mail and agent mailboxes only.
    public func threads(in mailbox: Mailbox, order: ThreadOrder = .newestFirst) -> [ThreadSummary] {
        switch mailbox {
        case .inbox: inbox
        case .undelivered: undelivered
        case .allMail: everything(order)
        case let .agent(name): everything(order).filter { $0.participants.contains(name) }
        }
    }

    private func everything(_ order: ThreadOrder) -> [ThreadSummary] {
        switch order {
        case .newestFirst: allMail
        case .openFirst: allMailOpenFirst
        }
    }

    /// The count shown next to a mailbox: unread messages for the Inbox,
    /// threads for Undelivered, the agent's own unread messages for an agent.
    public func badge(for mailbox: Mailbox) -> Int {
        switch mailbox {
        case .inbox: unreadInInbox
        case .undelivered: undelivered.count
        case .allMail: 0
        case let .agent(name): agents.first { $0.name == name }?.unread ?? 0
        }
    }

    /// Unread messages in threads waiting on the user: the menu bar count.
    public var unreadInInbox: Int { inbox.reduce(0) { $0 + $1.unread } }

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
