import Foundation
import Testing
@testable import AgentMailKit

@Suite struct MailboxTests {
    let index: MailboxIndex

    init() throws {
        index = MailboxIndex(status: try AgentMailJSON.decodeStatus(Fixture.data("status")))
    }

    @Test func inboxHoldsOpenThreadsWaitingOnTheUser() {
        #expect(index.inbox.map(\.number) == [2, 1])
        #expect(index.badge(for: .inbox) == 2)
        #expect(index.unreadInInbox == 2)
    }

    @Test func inboxCountsOnlyUnreadMessagesToTheUser() throws {
        // #1: website asked infra, infra handed it to the user. Both are
        // unread, but only infra's message came to the user.
        let deploy = try #require(index.thread("2026-10-31-deploy-the-new-site"))
        #expect(deploy.unread == 2)
        #expect(deploy.unreadToMe == 1)
        #expect(index.inbox.map(\.unread) == [1, 2], "the thread's own unread count stays")
        #expect(index.unreadInInbox == 2, "not 3: website's message to infra isn't the user's mail")

        // Agents talk it over, then one message reaches the user: the badge
        // says 1, not 4.
        let relayed = ThreadSummary(id: "r", number: 9, subject: "Relayed", owner: "user", count: 4, unread: 4,
                                    unreadToMe: 1, participants: ["infra", "user", "website"])
        let busy = MailboxIndex(status: MailStatus(now: .now, me: "user", threads: [relayed]))
        #expect(busy.badge(for: .inbox) == 1)
        #expect(busy.unreadInInbox == 1)
        #expect(busy.badge(for: .agent("user")) == 1, "the user's row still counts threads")
    }

    @Test func inboxCountsAllUnreadFromACLIWithoutTheField() throws {
        var json = try #require(String(data: Fixture.data("status"), encoding: .utf8))
        json = json.replacingOccurrences(of: #"(?m)^\s*"unread-to-me" : \d+,\n"#, with: "", options: .regularExpression)
        #expect(!json.contains("unread-to-me"))
        let older = MailboxIndex(status: try AgentMailJSON.decodeStatus(Data(json.utf8)))
        #expect(older.allMail.allSatisfy { $0.unreadToMe == $0.unread })
        #expect(older.unreadInInbox == 3, "every unread message, as before")
        #expect(ThreadSummary(id: "x", subject: "s", unread: 2).unreadToMe == 2, "made without it: the unread count")
    }

    @Test func undeliveredFollowsTheCLIsOrder() {
        #expect(index.undelivered.map(\.number) == [4])
        #expect(index.badge(for: .undelivered) == 1)
    }

    @Test func allMailIsNewestFirstByDefault() {
        #expect(index.allMail.map(\.number) == [4, 3, 2, 1])
        #expect(index.threads(in: .allMail).map(\.number) == [4, 3, 2, 1])
        #expect(index.threads(in: .allMail, order: .newestFirst).map(\.number) == [4, 3, 2, 1])
        #expect(index.threads(in: .agent("website")).map(\.number) == [3, 2, 1])
    }

    @Test func openFirstPutsClosedThreadsLast() {
        #expect(index.threads(in: .allMail, order: .openFirst).map(\.number) == [4, 2, 1, 3])
        #expect(index.threads(in: .agent("website"), order: .openFirst).map(\.number) == [2, 1, 3])
        #expect(index.threads(in: .agent("infra"), order: .openFirst).map(\.number) == [4, 1, 3])
    }

    @Test func inboxAndUndeliveredIgnoreTheOrder() {
        for order in ThreadOrder.allCases {
            #expect(index.threads(in: .inbox, order: order).map(\.number) == [2, 1])
            #expect(index.threads(in: .undelivered, order: order).map(\.number) == [4])
        }
    }

    @Test func ordersBreakTiesByNumber() {
        let early = Date(timeIntervalSince1970: 1000), late = Date(timeIntervalSince1970: 2000)
        let st = MailStatus(now: late, me: "user", threads: [
            ThreadSummary(id: "a", number: 1, subject: "a", updated: early, participants: ["user", "x"]),
            ThreadSummary(id: "b", number: 2, subject: "b", status: "done", updated: late, participants: ["x", "y"]),
            ThreadSummary(id: "c", number: 3, subject: "c", updated: early, participants: ["user", "x"]),
            ThreadSummary(id: "d", number: 4, subject: "d", created: late, participants: ["user", "y"]),
        ])
        let index = MailboxIndex(status: st)
        #expect(index.threads(in: .allMail).map(\.id) == ["d", "b", "c", "a"], "no updated: its created date")
        #expect(index.threads(in: .allMail, order: .openFirst).map(\.id) == ["d", "c", "a", "b"])
        #expect(index.threads(in: .agent("y")).map(\.id) == ["d", "b"])
        #expect(index.threads(in: .agent("y"), order: .openFirst).map(\.id) == ["d", "b"])
    }

    @Test func threadOrderTitlesAndCoding() throws {
        #expect(ThreadOrder.allCases == [.newestFirst, .openFirst])
        #expect(ThreadOrder.allCases.map(\.title) == ["Newest First", "Open First"])
        let data = try JSONEncoder().encode(ThreadOrder.openFirst)
        #expect(String(data: data, encoding: .utf8) == #""openFirst""#)
        #expect(try JSONDecoder().decode(ThreadOrder.self, from: data) == .openFirst)
        #expect(ThreadOrder(rawValue: "newestFirst") == .newestFirst)
    }

    @Test func participantsAreMeFirstThenTheReposByName() {
        #expect(index.participants.map(\.name) == ["user", "infra", "website"])
        #expect(index.participants.map(\.isMe) == [true, false, false])
        #expect(index.agents.map(\.name) == ["infra", "website"], "the repos alone, for New Message and On Duty")
        #expect(index.participant("infra")?.onDuty == false)
        #expect(index.participant("nobody") == nil)
        #expect(index.threads(in: .agent("website")).map(\.number) == [3, 2, 1])
    }

    @Test func myMailboxIsEveryThreadITakePartIn() {
        #expect(index.threads(in: .agent("user")).map(\.number) == [4, 2, 1])
        #expect(index.threads(in: .agent("user"), order: .openFirst).map(\.number) == [4, 2, 1])
        let closedToo = MailboxIndex(status: MailStatus(now: .now, me: "user", threads: [
            ThreadSummary(id: "a", number: 1, subject: "a", status: "done", updated: Date(timeIntervalSince1970: 2000),
                          participants: ["user", "x"]),
            ThreadSummary(id: "b", number: 2, subject: "b", updated: Date(timeIntervalSince1970: 1000),
                          participants: ["user", "x"]),
            ThreadSummary(id: "c", number: 3, subject: "c", updated: Date(timeIntervalSince1970: 3000),
                          participants: ["x", "y"]),
        ]))
        #expect(closedToo.threads(in: .agent("user")).map(\.id) == ["a", "b"])
        #expect(closedToo.threads(in: .agent("user"), order: .openFirst).map(\.id) == ["b", "a"])
    }

    @Test func participantFiltersSplitAParticipantsThreads() {
        func numbers(_ name: String, _ filter: ParticipantFilter, _ order: ThreadOrder = .newestFirst) -> [Int?] {
            index.threads(in: .agent(name), order: order, filter: filter).map(\.number)
        }
        #expect(numbers("user", .all) == [4, 2, 1])
        #expect(numbers("user", .sent) == [4])
        #expect(numbers("user", .received) == [2, 1])
        #expect(numbers("user", .waiting) == [2, 1])
        #expect(numbers("infra", .all) == [4, 3, 1])
        #expect(numbers("infra", .sent) == [3])
        #expect(numbers("infra", .received) == [4, 1])
        #expect(numbers("infra", .waiting) == [4], "#3 is infra's turn but closed")
        #expect(numbers("website", .sent) == [2, 1])
        #expect(numbers("website", .received) == [3])
        #expect(numbers("website", .waiting) == [])
        #expect(numbers("infra", .received, .openFirst) == [4, 1])
        #expect(numbers("website", .all, .openFirst) == [2, 1, 3])
        #expect(index.threads(in: .agent("infra")) == index.threads(in: .agent("infra"), filter: .all), "All is the default")
    }

    @Test func sentAndReceivedPartitionAll() {
        for p in index.participants {
            for order in ThreadOrder.allCases {
                let all = index.threads(in: .agent(p.name), order: order).map(\.id)
                let sent = index.threads(in: .agent(p.name), order: order, filter: .sent).map(\.id)
                let received = index.threads(in: .agent(p.name), order: order, filter: .received).map(\.id)
                #expect(Set(sent).isDisjoint(with: received), "\(p.name)")
                #expect(Set(sent).union(received) == Set(all), "\(p.name)")
                #expect(all.filter(Set(sent).contains) == sent, "the filter keeps the order")
            }
        }
    }

    @Test func filtersApplyToParticipantMailboxesOnly() {
        for filter in ParticipantFilter.allCases {
            #expect(index.threads(in: .inbox, filter: filter) == index.inbox)
            #expect(index.threads(in: .undelivered, filter: filter) == index.undelivered)
            #expect(index.threads(in: .allMail, filter: filter) == index.allMail)
        }
    }

    @Test func filterCountsMatchTheListsAndTheBadge() {
        for p in index.participants {
            for filter in ParticipantFilter.allCases {
                #expect(index.count(filter, for: p.name) == index.threads(in: .agent(p.name), filter: filter).count)
            }
            #expect(index.count(.waiting, for: p.name) == p.waiting, "Waiting is the sidebar badge")
        }
        #expect(index.count(.waiting, for: "user") == index.inbox.count)
        #expect(index.count(.all, for: "nobody") == 0)
    }

    @Test func filtersNameTheirParticipant() throws {
        #expect(ParticipantFilter.allCases == [.all, .sent, .received, .waiting])
        #expect(ParticipantFilter.allCases.map(\.title) == ["All", "Sent", "Received", "Waiting"])
        #expect(ParticipantFilter.allCases.map { $0.explanation(for: "infra", isMe: false) } == [
            "Every thread infra takes part in", "Threads infra started",
            "Threads others started that infra takes part in", "Open threads waiting on infra",
        ])
        #expect(ParticipantFilter.allCases.map { $0.explanation(for: "user", isMe: true) } == [
            "Every thread you take part in", "Threads you started",
            "Threads others started that you take part in", "Open threads waiting on you",
        ])
        #expect(ParticipantFilter.allCases.map { $0.emptyText(for: "infra", isMe: false) } == [
            "No threads with infra", "infra hasn’t started a thread", "No threads others started with infra",
            "Nothing waiting on infra",
        ])
        #expect(ParticipantFilter.allCases.map { $0.emptyText(for: "user", isMe: true) } == [
            "No threads yet", "You haven’t started a thread", "No threads others started with you",
            "Nothing waiting on you",
        ])
        let data = try JSONEncoder().encode(ParticipantFilter.received)
        #expect(String(data: data, encoding: .utf8) == #""received""#)
        #expect(try JSONDecoder().decode(ParticipantFilter.self, from: data) == .received)
        #expect(ParticipantFilter(rawValue: "waiting") == .waiting)
    }

    @Test func forwardsAreFoundByTheirSource() throws {
        let fwd = MailboxIndex(status: try AgentMailJSON.decodeStatus(Fixture.data("status-forward")))
        let forwards = fwd.forwards(of: "2026-11-03-rotate-the-certificate")
        #expect(forwards.map(\.number) == [2, 3], "in the order they were started")
        #expect(forwards.map(\.firstRecipient) == ["docs", "infra"])
        #expect(fwd.forwards(of: "2026-11-03-fwd-rotate-the-certificate").isEmpty)
        #expect(index.forwards(of: "2026-10-31-deploy-the-new-site").isEmpty)
    }

    @Test func firstRecipientFromTheSummary() {
        #expect(ThreadSummary(id: "a", subject: "s", from: "user", owner: "docs", flow: ["user", "docs"]).firstRecipient == "docs")
        #expect(ThreadSummary(id: "b", subject: "s", from: "user", owner: "user", flow: ["user", "infra", "user"])
            .firstRecipient == "infra", "the recipient wrote back")
        #expect(ThreadSummary(id: "c", subject: "s", from: "user", owner: "infra").firstRecipient == "infra",
                "an older CLI without flow: the owner")
        #expect(ThreadSummary(id: "d", subject: "s", from: "user", owner: "user", participants: ["user", "docs"])
            .firstRecipient == "docs")
        #expect(ThreadSummary(id: "e", subject: "s").firstRecipient == nil)
    }

    @Test func participantBadgesCountThreadsWaitingOnThem() {
        #expect(index.participants.map(\.waiting) == [2, 1, 0])
        #expect(index.badge(for: .agent("user")) == index.inbox.count)
        #expect(index.badge(for: .agent("user")) == 2, "threads, while the Inbox badge counts messages")
        #expect(index.badge(for: .inbox) == 2, "#1's and #2's one message each to the user")
        #expect(index.badge(for: .agent("infra")) == 1, "#4; infra's unread closing note on #3 doesn't count")
        #expect(index.badge(for: .agent("website")) == 0, "nothing open waits on website: no badge")
        #expect(index.badge(for: .agent("nobody")) == 0)
        #expect(index.badge(for: .allMail) == 0)
    }

    @Test func participantsSayWhatTheirBadgeCounts() throws {
        let me = try #require(index.participant("user"))
        #expect(me.waitingText == "2 threads waiting on you")
        #expect(me.tooltip == "you (user): every thread you take part in\n2 threads waiting on you")
        #expect(me.accessibilityLabel == "user (you), 2 threads waiting on you")
        let infra = try #require(index.participant("infra"))
        #expect(infra.waitingText == "1 thread waiting on infra")
        #expect(infra.tooltip == "infra: nobody on duty\n1 thread waiting on infra")
        #expect(infra.accessibilityLabel == "infra, not on duty, 1 thread waiting on infra")
        let website = try #require(index.participant("website"))
        #expect(website.waitingText == "Nothing waiting on website")
        #expect(website.tooltip == "website: nobody on duty\nNothing waiting on website")
        #expect(website.accessibilityLabel == "website, not on duty", "no badge, no count")
        let idle = AgentInfo(name: "user", isMe: true)
        #expect(idle.waitingText == "Nothing waiting on you")
        #expect(idle.accessibilityLabel == "user (you)")
    }

    @Test func dutyMarksAgentsOnDuty() throws {
        let duty = MailboxIndex(status: try AgentMailJSON.decodeStatus(Fixture.data("status-duty")))
        #expect(duty.onDuty.map(\.name) == ["website"])
        let website = try #require(duty.participant("website"))
        #expect(website.sessions.first?.session == "s1")
        #expect(website.tooltip == "website: on duty — s1 (waiting)\nNothing waiting on website")
        #expect(website.accessibilityLabel == "website, on duty")
        #expect(duty.participant("user")?.onDuty == false)
    }

    @Test func onCallReposSayServeDelivers() throws {
        func index(alive: Bool) -> MailboxIndex {
            MailboxIndex(status: MailStatus(now: .now, me: "user", threads: [],
                                            duty: [DutyRecord(repo: "website", session: "s1", onDuty: true)],
                                            oncall: [OnCallRepo(repo: "infra"), OnCallRepo(repo: "website"),
                                                     OnCallRepo(repo: "docs", state: "backing-off",
                                                                reason: "the folder is gone")],
                                            serve: ServeInfo(alive: alive, pid: 1)))
        }
        let up = index(alive: true)
        #expect(up.agents.map(\.name) == ["docs", "infra", "website"], "on-call repos are listed without threads")
        let infra = try #require(up.participant("infra"))
        #expect(infra.onCall && infra.delivering && !infra.onDuty)
        #expect(!infra.inDirectory, "a CLI from before the directory")
        #expect(infra.tooltip == "infra: on call — serve starts a session for each thread\nNothing waiting on infra")
        #expect(infra.accessibilityLabel == "infra, on call")
        #expect(up.participant("website")?.tooltip == "website: on duty — s1 (waiting)\nNothing waiting on website",
                "on duty wins")
        let docs = try #require(up.participant("docs"))
        #expect(docs.onCall && !docs.delivering, "backing off: on call, but nothing comes")
        #expect(docs.tooltip == "docs: on call, but serve backs off from it: the folder is gone\nNothing waiting on docs")
        let down = try #require(index(alive: false).participant("infra"))
        #expect(down.onCall && !down.delivering)
        #expect(down.tooltip == "infra: on call, but serve isn’t running\nNothing waiting on infra")
    }

    @Test func theDirectoryListsEveryAgentAndKeepsOtherNames() throws {
        let dir = MailboxIndex(status: try AgentMailJSON.decodeStatus(Fixture.data("status-directory")))
        #expect(dir.participants.map(\.name) == ["user", "docs", "gone", "infra", "notes", "old-repo", "website"],
                "docs and notes have no mail; old-repo left the directory but has a thread")
        #expect(dir.participants.map(\.inDirectory) == [false, true, true, true, true, false, true])
        #expect(dir.participants.map(\.onCall) == [false, true, true, true, false, false, true])
        #expect(dir.directory?.count == 5)
        #expect(dir.participant("user")?.entry == nil, "the user is never an agent")

        let infra = try #require(dir.participant("infra"))
        #expect(infra.summary == "Owns the servers, DNS and deploys.")
        #expect(infra.tooltip.hasPrefix("infra: on call — serve starts a session for each thread\n"
            + "Owns the servers, DNS and deploys.\n"))
        #expect(infra.tooltip.hasSuffix("\n1 thread waiting on infra"))
        #expect(dir.participant("website")?.tooltip == """
            website: on call — serve starts a session for each thread
            The public website: its pages, build and hosting. (from README.md)
            1 thread waiting on website
            """)
        let notes = try #require(dir.participant("notes"))
        #expect(notes.tooltip == """
            notes: off call — its mail waits until a session starts in its folder
            Keeps meeting notes.
            Nothing waiting on notes
            """)
        #expect(notes.accessibilityLabel == "notes, off call")
        #expect(notes.notices.isEmpty, "untrusted, but off call: serve wouldn't go there anyway")
        #expect(dir.participant("old-repo")?.tooltip == "old-repo: not in the agent directory\n1 thread waiting on old-repo")
        #expect(dir.participant("old-repo")?.accessibilityLabel == "old-repo, not on duty, 1 thread waiting on old-repo")
    }

    @Test func foldersServeCantUseAreFlagged() throws {
        let dir = MailboxIndex(status: try AgentMailJSON.decodeStatus(Fixture.data("status-directory")))
        let docs = try #require(dir.participant("docs"))
        #expect(docs.notices.map(\.kind) == [.untrusted])
        #expect(docs.notices.first?.text == "Claude Code hasn’t trusted /x/docs yet, so serve can’t start a session "
            + "there: run `claude` in it once and accept the question.")
        #expect(docs.accessibilityLabel == "docs, on call, 1 warning")
        #expect(docs.tooltip == """
            docs: on call — serve starts a session for each thread
            Owns the documentation site.
            \(docs.notices[0].text)
            Nothing waiting on docs
            """)
        let gone = try #require(dir.participant("gone"))
        #expect(gone.notices.map(\.kind) == [.folderGone])
        #expect(gone.notices.first?.text == "Its folder /x/gone is gone, so serve can’t start a session there: "
            + "`agent-mail agents move gone DIR` gives it the new one.")
        #expect(dir.participant("website")?.notices.isEmpty == true)
        #expect(dir.participant("old-repo")?.notices.isEmpty == true)
        let offCallGone = AgentInfo(name: "x", entry: DirectoryEntry(name: "x", dir: "/x/x", onCall: false, dirExists: false))
        #expect(offCallGone.notices.map(\.kind) == [.folderGone], "a folder that is gone is flagged on call or off")
    }

    @Test func thePostmasterIsServesRole() {
        let st = MailStatus(now: .now, me: "user", threads: [
            ThreadSummary(id: "n", subject: "postmaster: 2 threads wait on infra", from: "postmaster", owner: "user",
                          participants: ["postmaster", "user"]),
        ], agents: [])
        let index = MailboxIndex(status: st)
        #expect(index.agents.map(\.name) == ["postmaster"])
        #expect(index.participant("postmaster")?.tooltip
            == "postmaster: serve’s postmaster; mail to it goes to the agent agent-mail\nNothing waiting on postmaster")
    }

    @Test func agentsSeenOnlyOnDutyAreListed() {
        let st = MailStatus(now: .now, me: "user", threads: [],
                            duty: [DutyRecord(repo: "docs", session: "s", onDuty: true)])
        #expect(MailboxIndex(status: st).agents.map(\.name) == ["docs"])
        #expect(MailboxIndex(status: st).participants.map(\.name) == ["user", "docs"])
    }

    @Test func meComesFirstWhateverItsName() {
        let st = MailStatus(now: .now, me: "zoe", threads: [
            ThreadSummary(id: "a", subject: "a", owner: "zoe", participants: ["zoe", "website"]),
            ThreadSummary(id: "b", subject: "b", owner: "docs", participants: ["agent-rules", "docs"]),
        ])
        let index = MailboxIndex(status: st)
        #expect(index.participants.map(\.name) == ["zoe", "agent-rules", "docs", "website"])
        #expect(index.participants.map(\.waiting) == [1, 0, 1, 0])
        #expect(index.participant("zoe")?.tooltip == "you (zoe): every thread you take part in\n1 thread waiting on you")
    }

    @Test func theUserIsListedBeforeAnythingLoads() {
        #expect(MailboxIndex.empty.participants == [AgentInfo(name: "user", isMe: true)])
        #expect(MailboxIndex.empty.agents.isEmpty)
        #expect(MailboxIndex.empty.badge(for: .agent("user")) == 0)
    }

    @Test func myMailboxCodesLikeAnyParticipant() throws {
        let data = try JSONEncoder().encode(Mailbox.agent("user"))
        #expect(try JSONDecoder().decode(Mailbox.self, from: data) == .agent("user"))
        #expect(Mailbox.agent("user").title == "user")
    }

    @Test func preferredMailbox() {
        #expect(index.preferredMailbox(for: "2026-10-31-deploy-the-new-site") == .inbox)
        #expect(index.preferredMailbox(for: "2026-10-31-please-rotate-the-tls-certificate") == .undelivered)
        #expect(index.preferredMailbox(for: "2026-10-31-dns-for-docs-is-ready") == .allMail)
        #expect(index.preferredMailbox(for: "nope") == .allMail)
    }

    @Test func replyRecipientFromSummary() throws {
        let t = try #require(index.thread("2026-10-31-please-rotate-the-tls-certificate"))
        #expect(t.replyRecipient(me: "user") == "infra")
        let d = try #require(index.thread("2026-10-31-deploy-the-new-site"))
        #expect(d.replyRecipient(me: "user") == "infra")
    }

    @Test func fingerprintIgnoresReading() throws {
        var t = try #require(index.thread("2026-10-31-deploy-the-new-site"))
        let before = t.contentFingerprint
        t.unread = 0
        t.unreadBy["user"] = 0
        #expect(t.contentFingerprint == before)
        t.count += 1
        #expect(t.contentFingerprint != before)
    }

    @Test func ages() {
        #expect(Formatting.age(seconds: 45) == "45s")
        #expect(Formatting.age(seconds: 125) == "2m")
        #expect(Formatting.age(seconds: 3 * 3600 + 5) == "3h")
        #expect(Formatting.age(seconds: 2 * 86400) == "2d")
        #expect(Formatting.age(seconds: -5) == "0s")
        #expect(Formatting.spokenAge(seconds: 60) == "1 minute")
        #expect(Formatting.isValidName("infra"))
        #expect(!Formatting.isValidName("in fra"))
        #expect(!Formatting.isValidName("a/b"))
        #expect(!Formatting.isValidName(""))
    }

    @Test func flowLabelsKeepAtMostFourNames() {
        #expect(FlowLabel([]).text == "")
        #expect(FlowLabel(["a"]).text == "a")
        #expect(FlowLabel(["a", "b"]).text == "a → b")
        #expect(FlowLabel(["a", "b", "a"]).text == "a → b → a")
        #expect(FlowLabel(["a", "b", "c", "a"]).text == "a → b → c → a")
        #expect(!FlowLabel(["a", "b", "c", "a"]).isShortened)
        let long = FlowLabel(["a", "b", "c", "d", "e", "a"])
        #expect(long.text == "a → … → e → a")
        #expect(long.steps == [.name("a"), .gap(3), .name("e"), .name("a")])
        #expect(long.isShortened)
        #expect(long.fullText == "a → b → c → d → e → a")
        #expect(FlowLabel(["a", "b", "a", "b", "a"]).text == "a → … → b → a")
        #expect(FlowLabel(["a", "b", "a", "b", "a"]).steps[1] == .gap(2))
    }

    @Test func flowLabelsSpoken() {
        #expect(FlowLabel([]).spoken == "")
        #expect(FlowLabel(["a"]).spoken == "a")
        #expect(FlowLabel(["a", "b"]).spoken == "a to b")
        #expect(FlowLabel(["a", "b", "a"]).spoken == "a to b, back to a")
        #expect(FlowLabel(["a", "b", "c"]).spoken == "a to b, then c")
        #expect(FlowLabel(["a", "b", "c", "a"]).spoken == "a to b, then c, back to a")
        #expect(FlowLabel(["a", "b", "c", "d", "e", "a"]).spoken == "a, then 3 others, then e, back to a")
        #expect(FlowLabel(["a", "b", "c", "b", "a"]).spoken == "a, then 2 others, back to b, back to a")
    }

    @Test func shownFlowFallsBackToLastSenderAndOwner() throws {
        let deploy = try #require(index.thread("2026-10-31-deploy-the-new-site"))
        #expect(deploy.shownFlow == ["website", "infra", "user"])
        let old = ThreadSummary(id: "x", subject: "s", from: "a", lastFrom: "b", owner: "c")
        #expect(old.shownFlow == ["b", "c"], "a CLI without flow: last sender, then owner")
        #expect(ThreadSummary(id: "y", subject: "s", from: "a", lastFrom: "a", owner: "a").shownFlow == ["a"])
        #expect(ThreadSummary(id: "z", subject: "s", from: "a").shownFlow == ["a"], "no messages: who started it")
    }
}
