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

    public init(schema: Int = MailStatus.supportedSchema, now: Date, me: String,
                threads: [ThreadSummary], duty: [DutyRecord] = [], undelivered: [String] = []) {
        self.schema = schema
        self.now = now
        self.me = me
        self.threads = threads
        self.duty = duty
        self.undelivered = undelivered
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
    /// Unread count for each participant.
    public var unreadBy: [String: Int]
    public var participants: [String]
    /// Seconds an open thread has waited on its owner; nil once closed.
    public var waitingSeconds: Int?
    /// `user`, `on-duty` or `none` for an open thread; nil when closed.
    public var listener: String?

    enum CodingKeys: String, CodingKey {
        case id, number, subject, from, created, owner, status, count, updated, unread, participants, listener
        case lastFrom = "last-from"
        case unreadBy = "unread-by"
        case waitingSeconds = "waiting-s"
    }

    public init(id: String, number: Int? = nil, subject: String, from: String? = nil, lastFrom: String? = nil,
                created: Date? = nil, owner: String? = nil, status: String = "open", count: Int = 1,
                updated: Date? = nil, unread: Int = 0, unreadBy: [String: Int] = [:], participants: [String] = [],
                waitingSeconds: Int? = nil, listener: String? = nil) {
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
        self.unreadBy = unreadBy
        self.participants = participants
        self.waitingSeconds = waitingSeconds
        self.listener = listener
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
        unreadBy = try c.decodeIfPresent([String: Int].self, forKey: .unreadBy) ?? [:]
        participants = try c.decodeIfPresent([String].self, forKey: .participants) ?? []
        waitingSeconds = try c.decodeIfPresent(Int.self, forKey: .waitingSeconds)
        listener = try c.decodeIfPresent(String.self, forKey: .listener)
    }

    public var isOpen: Bool { status == "open" }

    /// Open and waiting on `me`.
    public func isWaiting(on me: String) -> Bool { isOpen && owner == me }

    /// Open, and nobody is listening for its owner.
    public var isUndelivered: Bool { listener == "none" }

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
    public var msgs: [Message]

    enum CodingKeys: String, CodingKey {
        case id, number, subject, from, created, owner, updated, status, count, msgs
        case lastFrom = "last-from"
    }

    public init(id: String, number: Int? = nil, subject: String, from: String? = nil, created: Date? = nil,
                owner: String? = nil, lastFrom: String? = nil, updated: Date? = nil, status: String = "open",
                msgs: [Message]) {
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
