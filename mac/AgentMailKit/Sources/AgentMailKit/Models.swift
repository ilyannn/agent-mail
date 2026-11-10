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
    /// The agent directory, by name: the names mail may go to (with `user`
    /// and `postmaster`). nil from a CLI that predates the directory, which
    /// isn't the same as an empty one.
    public var agents: [DirectoryEntry]?

    enum CodingKeys: String, CodingKey {
        case schema, now, me, threads, duty, undelivered, oncall, serve, agents
    }

    public init(schema: Int = MailStatus.supportedSchema, now: Date, me: String,
                threads: [ThreadSummary], duty: [DutyRecord] = [], undelivered: [String] = [],
                oncall: [OnCallRepo] = [], serve: ServeInfo? = nil, agents: [DirectoryEntry]? = nil) {
        self.schema = schema
        self.now = now
        self.me = me
        self.threads = threads
        self.duty = duty
        self.undelivered = undelivered
        self.oncall = oncall
        self.serve = serve
        self.agents = agents
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
        agents = try c.decodeIfPresent([DirectoryEntry].self, forKey: .agents)
    }

    /// The repos for which a listener tab opens when mail comes: on call,
    /// not backing off, while serve runs (core/status's rule).
    public var onCallRepos: Set<String> {
        guard serve?.alive == true else { return [] }
        return Set(oncall.filter { $0.state != "backing-off" }.map(\.repo))
    }
}

/// A repo on call, as `status` lists it (core/oncall-view): what serve is
/// doing about it.
public struct OnCallRepo: Sendable, Equatable, Hashable, Decodable {
    public var repo: String
    /// The agent's folder, where serve starts its sessions.
    public var dir: String?
    /// `on-duty`, `backing-off` or `on-call` (older CLIs: also `starting`
    /// and `tab-open`).
    public var state: String
    /// The WezTerm pane of the tab an older serve opened for it; always nil
    /// since serve starts sessions instead.
    public var pane: Int?
    /// The CLI's line about it, as `agent-mail oncall` prints it.
    public var text: String?
    /// While backing off: until when, and why.
    public var until: Date?
    public var reason: String?
    /// Why serve can't bring the repo's main checkout up to date with what
    /// one of its sessions pushed (core/land-plan's text); nil when it can.
    public var cannotLand: String?
    /// Serve's finished sessions kept because their worktree branch isn't
    /// merged; empty from a CLI that predates the field.
    public var unlanded: [UnlandedSession]

    enum CodingKeys: String, CodingKey {
        case repo, dir, state, pane, text, until, reason, unlanded
        case cannotLand = "cannot-land"
    }

    public init(repo: String, dir: String? = nil, state: String = "on-call", pane: Int? = nil, text: String? = nil,
                until: Date? = nil, reason: String? = nil, cannotLand: String? = nil,
                unlanded: [UnlandedSession] = []) {
        self.repo = repo
        self.dir = dir
        self.state = state
        self.pane = pane
        self.text = text
        self.until = until
        self.reason = reason
        self.cannotLand = cannotLand
        self.unlanded = unlanded
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        repo = try c.decode(String.self, forKey: .repo)
        dir = try c.decodeIfPresent(String.self, forKey: .dir)
        state = try c.decodeIfPresent(String.self, forKey: .state) ?? "on-call"
        pane = try c.decodeIfPresent(Int.self, forKey: .pane)
        text = try c.decodeIfPresent(String.self, forKey: .text)
        until = try c.decodeIfPresent(Date.self, forKey: .until)
        reason = try c.decodeIfPresent(String.self, forKey: .reason)
        cannotLand = try c.decodeIfPresent(String.self, forKey: .cannotLand).flatMap(\.nonBlank)
        unlanded = try c.decodeIfPresent([UnlandedSession].self, forKey: .unlanded) ?? []
    }

    public var isBackingOff: Bool { state == "backing-off" }
}

/// One of serve's finished sessions, kept because what it committed in its
/// worktree hasn't reached the main checkout (core/rm-plan).
public struct UnlandedSession: Sendable, Equatable, Hashable, Decodable {
    /// The short id `claude attach` takes.
    public var id: String
    public var name: String?
    /// Its worktree branch (a detached HEAD's commit); nil when git couldn't
    /// tell.
    public var branch: String?
    public var commits: Int?

    public init(id: String, name: String? = nil, branch: String? = nil, commits: Int? = nil) {
        self.id = id
        self.name = name
        self.branch = branch
        self.commits = commits
    }

    /// What is unlanded, as the CLI says it (core/unlanded-phrase): “📫 infra
    /// #3”: branch worktree-x, 2 commits.
    public var text: String {
        let who = "“\(name?.nonBlank ?? id)”"
        guard let branch = branch?.nonBlank else { return "\(who): branch unknown" }
        return "\(who): branch \(branch)" + (commits.map { ", \($0) commit\($0 == 1 ? "" : "s")" } ?? "")
    }
}

/// An agent in the directory, as `status` lists it (core/agent-view).
public struct DirectoryEntry: Sendable, Equatable, Hashable, Decodable {
    public var name: String
    /// Its folder (a real path).
    public var dir: String?
    /// Serve delivers its mail, one Claude Code session per thread.
    public var onCall: Bool
    /// What kind of agent: `claude`.
    public var kind: String
    /// The description's first line, else a paragraph borrowed from the
    /// folder's CLAUDE.md or README.md (see `borrowed`).
    public var summary: String?
    /// The file a borrowed summary comes from, e.g. `README.md`.
    public var borrowed: String?
    /// Whether Claude Code trusts the folder; nil when that is unknown.
    public var trusted: Bool?
    public var dirExists: Bool
    /// Its running Claude Code sessions, in its folder or below, with what
    /// each is doing; empty from a CLI that predates the field.
    public var sessions: [AgentSession]

    enum CodingKeys: String, CodingKey {
        case name, dir, kind, summary, borrowed, trusted, sessions
        case onCall = "oncall"
        case dirExists = "dir-exists"
    }

    public init(name: String, dir: String? = nil, onCall: Bool = true, kind: String = "claude", summary: String? = nil,
                borrowed: String? = nil, trusted: Bool? = nil, dirExists: Bool = true, sessions: [AgentSession] = []) {
        self.name = name
        self.dir = dir
        self.onCall = onCall
        self.kind = kind
        self.summary = summary
        self.borrowed = borrowed
        self.trusted = trusted
        self.dirExists = dirExists
        self.sessions = sessions
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        dir = try c.decodeIfPresent(String.self, forKey: .dir)
        onCall = try c.decodeIfPresent(Bool.self, forKey: .onCall) ?? false
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? "claude"
        summary = try c.decodeIfPresent(String.self, forKey: .summary).flatMap(\.nonBlank)
        borrowed = try c.decodeIfPresent(String.self, forKey: .borrowed).flatMap(\.nonBlank)
        trusted = try c.decodeIfPresent(Bool.self, forKey: .trusted)
        dirExists = try c.decodeIfPresent(Bool.self, forKey: .dirExists) ?? true
        sessions = try c.decodeIfPresent([AgentSession].self, forKey: .sessions) ?? []
    }

    /// The summary as `agent-mail agents` shows it: a borrowed one says
    /// where it comes from.
    public var shownSummary: String? {
        guard let summary else { return nil }
        return borrowed.map { "\(summary) (from \($0))" } ?? summary
    }
}

/// One of an agent's running Claude Code sessions, as `status` lists it
/// (core/agent-sessions): one in its folder or below, serve's or not.
public struct AgentSession: Sendable, Equatable, Hashable, Decodable {
    /// The short id `claude attach` takes.
    public var id: String
    /// Its name in `claude agents`, e.g. `📫 infra #12`; nil when it has none.
    public var name: String?
    /// `background` or `interactive`.
    public var kind: String?
    /// `busy` (in a turn), `waiting` (on the user: a permission prompt or
    /// another dialog) or `idle`.
    public var activity: String?

    public init(id: String, name: String? = nil, kind: String? = nil, activity: String? = nil) {
        self.id = id
        self.name = name
        self.kind = kind
        self.activity = activity
    }

    /// In a turn: actually working.
    public var isWorking: Bool { activity == "busy" }
    /// Blocked on the user, a permission prompt or another dialog.
    public var isWaitingOnUser: Bool { activity == "waiting" }

    /// Its name, else its short id.
    public var shownName: String { name?.nonBlank ?? id }
}

/// The session serve handed a thread to, as it last saw it
/// (core/thread-session).
public struct ThreadSession: Sendable, Equatable, Hashable, Decodable {
    /// The short id `claude attach` takes.
    public var id: String
    /// Its name in `claude agents`, e.g. `📫 infra #12`.
    public var name: String?
    /// `background` or `interactive`.
    public var kind: String?
    /// `starting`, `live`, `stopped` or `gone`.
    public var state: String?
    /// What it is doing: `busy`, `idle` or `waiting` while it runs, else its
    /// state; nil from a CLI that predates the field.
    public var activity: String?
    /// Since when it has waited on the user (a permission prompt or another
    /// dialog), what for, and what it needs; nil unless it waits.
    public var waitingSince: Date?
    public var waitingFor: String?
    public var needs: String?

    enum CodingKeys: String, CodingKey {
        case id, name, kind, state, activity, needs
        case waitingSince = "waiting-since"
        case waitingFor = "waiting-for"
    }

    public init(id: String, name: String? = nil, kind: String? = nil, state: String? = nil, activity: String? = nil,
                waitingSince: Date? = nil, waitingFor: String? = nil, needs: String? = nil) {
        self.id = id
        self.name = name
        self.kind = kind
        self.state = state
        self.activity = activity
        self.waitingSince = waitingSince
        self.waitingFor = waitingFor
        self.needs = needs
    }

    public var isWaitingOnUser: Bool { waitingSince != nil }

    /// What the thread's header says it is doing: working while in a turn,
    /// idle between turns, waiting on you at a prompt, else its state
    /// (starting, stopped, gone; live from a CLI without activity).
    public var shownState: String? {
        switch activity {
        case "busy": "working"
        case "idle": "idle"
        case "waiting": "waiting on you"
        default: state
        }
    }

    /// The name SendMessage and `claude agents` know it by, else its id.
    public var shownName: String { name?.nonBlank ?? id }

    /// What it waits on the user for (core/needs-you): its need, else what
    /// it waits for, else input.
    public var need: String { needs?.nonBlank ?? waitingFor?.nonBlank ?? "input" }

    /// Opens it in a terminal.
    public var attachCommand: String { "claude attach \(id)" }
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
    /// The session serve handed the thread to, as serve last saw it; nil
    /// when serve delivered none of it, and from a CLI that predates serve's
    /// sessions.
    public var session: ThreadSession?

    enum CodingKeys: String, CodingKey {
        case id, number, subject, from, created, owner, status, count, updated, unread, participants, flow, listener
        case session
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
                forwardedFrom: String? = nil, session: ThreadSession? = nil) {
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
        self.session = session
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
        session = try c.decodeIfPresent(ThreadSession.self, forKey: .session)
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

extension String {
    /// The string, or nil when it is empty or only whitespace.
    var nonBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
