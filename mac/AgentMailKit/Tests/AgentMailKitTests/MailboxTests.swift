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
        #expect(index.badge(for: .inbox) == 3)
        #expect(index.unreadInInbox == 3)
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

    @Test func agentsAreEveryoneButMe() {
        #expect(index.agents.map(\.name) == ["infra", "website"])
        let infra = index.agents[0], website = index.agents[1]
        #expect(infra.unread == 2)
        #expect(infra.waiting == 1)
        #expect(!infra.onDuty)
        #expect(website.unread == 1)
        #expect(index.threads(in: .agent("website")).map(\.number) == [3, 2, 1])
        #expect(index.badge(for: .agent("infra")) == 2)
    }

    @Test func dutyMarksAgentsOnDuty() throws {
        let duty = MailboxIndex(status: try AgentMailJSON.decodeStatus(Fixture.data("status-duty")))
        #expect(duty.onDuty.map(\.name) == ["website"])
        #expect(duty.agents.first { $0.name == "website" }?.sessions.first?.session == "s1")
    }

    @Test func agentsSeenOnlyOnDutyAreListed() {
        let st = MailStatus(now: .now, me: "user", threads: [],
                            duty: [DutyRecord(repo: "docs", session: "s", onDuty: true)])
        #expect(MailboxIndex(status: st).agents.map(\.name) == ["docs"])
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
