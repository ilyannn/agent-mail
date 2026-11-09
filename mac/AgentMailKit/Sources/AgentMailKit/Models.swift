// The CLI's JSON as Swift values: `agent-mail status --json` (MailStatus) and
// `agent-mail read <id> --json` (ThreadDetail). Field names follow
// src/agent_mail/core.clj; optional fields are the ones the CLI may emit as
// null or leave out (a thread without messages has no owner or dates).
import Foundation

/// Everything a UI needs, from one `agent-mail status --json` run.
public struct MailStatus: Sendable, Equatable, Decodable {
    /// The status schema this client understands; others are refused.
    public static let supportedSchema = 1

    public var schema: Int
    public var now: Date
    /// Who the CLI ran as (always `user` from the app).
    public var me: String
    /// Every thread, closed ones too, newest-updated first.
    public var threads: [ThreadSummary]
    public var duty: [DutyRecord]
    /// Ids of open threads nobody listens for, longest waiting first.
    public var undelivered: [String]
    /// Repos on call (`agent-mail oncall`): `agent-mail serve` opens a
    /// listener tab for one when mail waits and nobody is on duty. Empty from
    /// a CLI that predates on call.
    public var oncall: [OnCallRepo]
    /// Whether `agent-mail serve` runs; nil from a CLI that predates it.
    public var serve: ServeInfo?

    enum CodingKeys: String, CodingKey {
        case schema, now, me, threads, duty, undelivered, oncall, serve
    }

    public init(schema: Int = MailStatus.supportedSchema, now: Date, me: String,
                threads: [ThreadSummary], duty: [DutyRecord] = [], undelivered: [String] = [],
                oncall: [OnCallRepo] = [], serve: ServeInfo? = nil) {
        self.schema = schema
        self.now = now
        self.me = me
        self.threads = threads
        self.duty = duty
        self.undelivered = undelivered
        self.oncall = oncall
        self.serve = serve
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decode(Int.self, forKey: .schema)
        now = try c.decode(Date.self, forKey: .now)
        me = try c.decode(String.self, forKey: .me)
        threads = try c.decode([ThreadSummary].self, forKey: .threads)
        duty = try c.decode([DutyRecord].self, forKey: .duty)
        undelivered = try c.decode([String].self, forKey: .undelivered)
        oncall = try c.decodeIfPresent([OnCallRepo].self, forKey: .oncall) ?? []
        serve = try c.decodeIfPresent(ServeInfo.self, forKey: .serve)
    }

    /// The repos for which a listener tab opens when mail comes: on call,
    /// not backing off, while serve runs (core/status's rule).
    public var onCallRepos: Set<String> {
        guard serve?.alive == true else { return [] }
        return Set(oncall.filter { $0.state != "backing-off" }.map(\.repo))
    }
}

/// A repo on call, as `status` lists it (core/oncall-view).
public struct OnCallRepo: Sendable, Equatable, Hashable, Decodable {
    public var repo: String
    /// Where its listener tab starts.
    public var dir: String?
    /// `on-duty`, `starting`, `tab-open`, `backing-off` or `on-call`.
    public var state: String
    /// The WezTerm pane of the tab serve opened for it, if one is open.
    public var pane: Int?

    public init(repo: String, dir: String? = nil, state: String = "on-call", pane: Int? = nil) {
        self.repo = repo
        self.dir = dir
        self.state = state
        self.pane = pane
    }
}

/// The on-call daemon, `agent-mail serve`.
public struct ServeInfo: Sendable, Equatable, Hashable, Decodable {
    public var alive: Bool
    public var pid: Int?

    public init(alive: Bool, pid: Int? = nil) {
        self.alive = alive
        self.pid = pid
    }
}

/// A thread without its messages, as `status` lists it.
public struct ThreadSummary: Sendable, Equatable, Hashable, Identifiable, Decodable {
    public var id: String
    public var number: Int?
    public var subject: String
    /// Who started the thread.
    public var from: String?
    public var lastFrom: String?
    public var created: Date?
    /// Whose turn it is: the `to` of the last message.
    public var owner: String?
    /// `open` or `done`.
    public var status: String
    public var count: Int
    public var updated: Date?
    /// Messages the caller (`me`) has not seen and did not write.
    public var unread: Int
    /// The unread messages addressed to the caller, leaving out what agents
    /// sent each other in the thread: what the Inbox counts. `unread` from a
    /// CLI that predates the field.
    public var unreadToMe: Int
    /// Unread count for each participant.
    public var unreadBy: [String: Int]
    public var participants: [String]
    /// Who the thread passed through (core/flow): its senders in written
    /// order, repeats collapsed, then the owner when it isn't the last sender.
    /// Empty from a CLI that predates the field; see `shownFlow`.
    public var flow: [String]
    /// Seconds an open thread has waited on its owner; nil once closed.
    public var waitingSeconds: Int?
    /// `user`, `on-duty`, `on-call` (a listener tab opens for the owner
    /// shortly) or `none` for an open thread; nil when closed.
    public var listener: String?
    /// The thread this one forwards (`agent-mail forward`), nil for any
    /// other thread and from a CLI that predates forwarding.
    public var forwardedFrom: String?

    enum CodingKeys: String, CodingKey {
        case id, number, subject, from, created, owner, status, count, updated, unread, participants, flow, listener
        case lastFrom = "last-from"
        case unreadToMe = "unread-to-me"
        case unreadBy = "unread-by"
        case waitingSeconds = "waiting-s"
        case forwardedFrom = "forwarded-from"
    }

    public init(id: String, number: Int? = nil, subject: String, from: String? = nil, lastFrom: String? = nil,
                created: Date? = nil, owner: String? = nil, status: String = "open", count: Int = 1,
                updated: Date? = nil, unread: Int = 0, unreadToMe: Int? = nil, unreadBy: [String: Int] = [:],
                participants: [String] = [], flow: [String] = [], waitingSeconds: Int? = nil, listener: String? = nil,
                forwardedFrom: String? = nil) {
        self.id = id
        self.number = number
        self.subject = subject
        self.from = from
        self.lastFrom = lastFrom
        self.created = created
        self.owner = owner
        self.status = status
        self.count = count
        self.updated = updated
        self.unread = unread
        self.unreadToMe = unreadToMe ?? unread
        self.unreadBy = unreadBy
        self.participants = participants
        self.flow = flow
        self.waitingSeconds = waitingSeconds
        self.listener = listener
        self.forwardedFrom = forwardedFrom
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        number = try c.decodeIfPresent(Int.self, forKey: .number)
        subject = try c.decodeIfPresent(String.self, forKey: .subject) ?? ""
        from = try c.decodeIfPresent(String.self, forKey: .from)
        lastFrom = try c.decodeIfPresent(String.self, forKey: .lastFrom)
        created = try c.decodeIfPresent(Date.self, forKey: .created)
        owner = try c.decodeIfPresent(String.self, forKey: .owner)
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "open"
        count = try c.decodeIfPresent(Int.self, forKey: .count) ?? 0
        updated = try c.decodeIfPresent(Date.self, forKey: .updated)
        unread = try c.decodeIfPresent(Int.self, forKey: .unread) ?? 0
        unreadToMe = try c.decodeIfPresent(Int.self, forKey: .unreadToMe) ?? unread
        unreadBy = try c.decodeIfPresent([String: Int].self, forKey: .unreadBy) ?? [:]
        participants = try c.decodeIfPresent([String].self, forKey: .participants) ?? []
        flow = try c.decodeIfPresent([String].self, forKey: .flow) ?? []
        waitingSeconds = try c.decodeIfPresent(Int.self, forKey: .waitingSeconds)
        listener = try c.decodeIfPresent(String.self, forKey: .listener)
        forwardedFrom = try c.decodeIfPresent(String.self, forKey: .forwardedFrom)
    }

    public var isOpen: Bool { status == "open" }

    /// Open and waiting on `me`.
    public func isWaiting(on me: String) -> Bool { isOpen && owner == me }

    /// Open, and nobody is listening for its owner.
    public var isUndelivered: Bool { listener == "none" }

    /// The flow to show: the CLI's, or from a CLI that predates `flow`, the
    /// last sender and the owner (what the list showed before).
    public var shownFlow: [String] {
        if !flow.isEmpty { return flow }
        return [lastFrom ?? from, owner].compactMap { $0 }.reduce(into: []) { names, name in
            if names.last != name { names.append(name) }
        }
    }

    /// What identifies the thread's content: changes when a message arrives or
    /// the status flips, not when someone merely reads it.
    public var contentFingerprint: String { "\(count)|\(updated?.timeIntervalSince1970 ?? 0)|\(status)" }

    /// Whom a reply from `me` goes to: the last writer that isn't me, else the
    /// owner (core/other-party, from the summary's fields).
    public func replyRecipient(me: String) -> String? {
        if let lastFrom, lastFrom != me { return lastFrom }
        if let owner, owner != me { return owner }
        return participants.first { $0 != me }
    }

    /// Whom the thread's first message went to, as far as the summary can
    /// tell: the second name of its flow (the first recipient wrote next, or
    /// still has the turn), else the owner, else whoever else takes part.
    /// Names a forward's recipient in the original's header.
    public var firstRecipient: String? {
        if flow.count > 1 { return flow[1] }
        if let owner, owner != from { return owner }
        return participants.first { $0 != from }
    }
}

/// One duty record: an agent session waiting for (or working on) mail.
public struct DutyRecord: Sendable, Equatable, Hashable, Decodable {
    public var repo: String
    public var session: String
    /// `waiting`, `working` or `idle`.
    public var state: String
    public var heartbeat: Date?
    public var pid: Int?
    public var sessionPid: Int?
    public var ageSeconds: Int?
    public var stale: Bool?
    public var sessionAlive: Bool?
    public var onDuty: Bool

    enum CodingKeys: String, CodingKey {
        case repo, session, state, heartbeat, pid, stale
        case sessionPid = "session-pid"
        case ageSeconds = "age-s"
        case sessionAlive = "session-alive"
        case onDuty = "on-duty"
    }

    public init(repo: String, session: String, state: String = "waiting", heartbeat: Date? = nil, pid: Int? = nil,
                sessionPid: Int? = nil, ageSeconds: Int? = nil, stale: Bool? = nil, sessionAlive: Bool? = nil,
                onDuty: Bool) {
        self.repo = repo
        self.session = session
        self.state = state
        self.heartbeat = heartbeat
        self.pid = pid
        self.sessionPid = sessionPid
        self.ageSeconds = ageSeconds
        self.stale = stale
        self.sessionAlive = sessionAlive
        self.onDuty = onDuty
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        repo = try c.decode(String.self, forKey: .repo)
        session = try c.decodeIfPresent(String.self, forKey: .session) ?? ""
        state = try c.decodeIfPresent(String.self, forKey: .state) ?? ""
        heartbeat = try c.decodeIfPresent(Date.self, forKey: .heartbeat)
        pid = try c.decodeIfPresent(Int.self, forKey: .pid)
        sessionPid = try c.decodeIfPresent(Int.self, forKey: .sessionPid)
        ageSeconds = try c.decodeIfPresent(Int.self, forKey: .ageSeconds)
        stale = try c.decodeIfPresent(Bool.self, forKey: .stale)
        sessionAlive = try c.decodeIfPresent(Bool.self, forKey: .sessionAlive)
        onDuty = try c.decodeIfPresent(Bool.self, forKey: .onDuty) ?? false
    }
}

/// A whole thread, from `agent-mail read <id> --json`.
public struct ThreadDetail: Sendable, Equatable, Decodable {
    public var id: String
    public var number: Int?
    public var subject: String
    public var from: String?
    public var created: Date?
    public var owner: String?
    public var lastFrom: String?
    public var updated: Date?
    public var status: String
    public var count: Int
    /// The thread this one forwards, as on ThreadSummary.
    public var forwardedFrom: String?
    public var msgs: [Message]

    enum CodingKeys: String, CodingKey {
        case id, number, subject, from, created, owner, updated, status, count, msgs
        case lastFrom = "last-from"
        case forwardedFrom = "forwarded-from"
    }

    public init(id: String, number: Int? = nil, subject: String, from: String? = nil, created: Date? = nil,
                owner: String? = nil, lastFrom: String? = nil, updated: Date? = nil, status: String = "open",
                forwardedFrom: String? = nil, msgs: [Message]) {
        self.id = id
        self.number = number
        self.subject = subject
        self.from = from
        self.created = created
        self.owner = owner
        self.lastFrom = lastFrom
        self.updated = updated
        self.status = status
        self.count = msgs.count
        self.forwardedFrom = forwardedFrom
        self.msgs = msgs
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        number = try c.decodeIfPresent(Int.self, forKey: .number)
        subject = try c.decodeIfPresent(String.self, forKey: .subject) ?? ""
        from = try c.decodeIfPresent(String.self, forKey: .from)
        created = try c.decodeIfPresent(Date.self, forKey: .created)
        owner = try c.decodeIfPresent(String.self, forKey: .owner)
        lastFrom = try c.decodeIfPresent(String.self, forKey: .lastFrom)
        updated = try c.decodeIfPresent(Date.self, forKey: .updated)
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "open"
        forwardedFrom = try c.decodeIfPresent(String.self, forKey: .forwardedFrom)
        msgs = try c.decodeIfPresent([Message].self, forKey: .msgs) ?? []
        count = try c.decodeIfPresent(Int.self, forKey: .count) ?? msgs.count
    }

    public var isOpen: Bool { status == "open" }

    /// Same form as ThreadSummary.contentFingerprint, so a loaded thread can
    /// be compared with the latest status.
    public var contentFingerprint: String { "\(count)|\(updated?.timeIntervalSince1970 ?? 0)|\(status)" }

    /// Every name the messages come from or go to, sorted (core/participants).
    public var participants: [String] {
        Array(Set(msgs.flatMap { [$0.from, $0.to].compactMap { $0 } })).sorted()
    }

    /// Whom a reply from `me` goes to: the last writer that isn't me, else the owner.
    public func replyRecipient(me: String) -> String? {
        if let m = msgs.last(where: { $0.from != me }), let from = m.from { return from }
        if let owner, owner != me { return owner }
        return nil
    }
}

/// One message file of a thread.
public struct Message: Sendable, Equatable, Hashable, Decodable, Identifiable {
    /// The message's file name, e.g. `2026-10-31T230503.415Z__infra__872a.md`;
    /// unique within the store, and the notification identifier.
    public var file: String
    public var from: String?
    public var to: String?
    public var date: Date?
    /// `done` on the message that closed the thread.
    public var status: String?
    /// `files/<name>` references into the thread's attachment directory.
    public var attachments: [String]
    public var body: String
    public var inReplyTo: String?

    public var id: String { file }

    enum CodingKeys: String, CodingKey {
        case file, from, to, date, status, attachments, body
        case inReplyTo = "in-reply-to"
    }

    public init(file: String, from: String?, to: String?, date: Date?, status: String? = nil,
                attachments: [String] = [], body: String, inReplyTo: String? = nil) {
        self.file = file
        self.from = from
        self.to = to
        self.date = date
        self.status = status
        self.attachments = attachments
        self.body = body
        self.inReplyTo = inReplyTo
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        file = try c.decode(String.self, forKey: .file)
        from = try c.decodeIfPresent(String.self, forKey: .from)
        to = try c.decodeIfPresent(String.self, forKey: .to)
        date = try c.decodeIfPresent(Date.self, forKey: .date)
        status = try c.decodeIfPresent(String.self, forKey: .status)
        attachments = try c.decodeIfPresent([String].self, forKey: .attachments) ?? []
        body = try c.decodeIfPresent(String.self, forKey: .body) ?? ""
        inReplyTo = try c.decodeIfPresent(String.self, forKey: .inReplyTo)
    }
}
