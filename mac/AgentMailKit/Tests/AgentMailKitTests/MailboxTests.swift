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

    @Test func allMailPutsClosedThreadsLast() {
        #expect(index.allMail.map(\.number) == [4, 2, 1, 3])
    }

    @Test func agentsAreEveryoneButMe() {
        #expect(index.agents.map(\.name) == ["infra", "website"])
        let infra = index.agents[0], website = index.agents[1]
        #expect(infra.unread == 2)
        #expect(infra.waiting == 1)
        #expect(!infra.onDuty)
        #expect(website.unread == 1)
        #expect(index.threads(in: .agent("website")).map(\.number) == [2, 1, 3])
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
}
