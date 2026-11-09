import Foundation
import Testing
@testable import AgentMailKit

@Suite struct DecodingTests {
    @Test func statusFixtureDecodes() throws {
        let st = try AgentMailJSON.decodeStatus(Fixture.data("status"))
        #expect(st.schema == 1)
        #expect(st.me == "user")
        #expect(st.now == Fixture.date("2026-10-31T23:05:27.069Z"))
        #expect(st.threads.map(\.number) == [4, 3, 2, 1])
        #expect(st.undelivered == ["2026-10-31-please-rotate-the-tls-certificate"])
        #expect(st.duty.isEmpty)

        let deploy = try #require(st.threads.first { $0.number == 1 })
        #expect(deploy.id == "2026-10-31-deploy-the-new-site")
        #expect(deploy.subject == "Deploy the new site")
        #expect(deploy.from == "website")
        #expect(deploy.lastFrom == "infra")
        #expect(deploy.owner == "user")
        #expect(deploy.status == "open")
        #expect(deploy.count == 2)
        #expect(deploy.unread == 2)
        #expect(deploy.unreadToMe == 1, "website's message went to infra")
        #expect(deploy.unreadBy == ["infra": 0, "user": 2, "website": 1])
        #expect(deploy.participants == ["infra", "user", "website"])
        #expect(deploy.flow == ["website", "infra", "user"])
        #expect(deploy.waitingSeconds == 24)
        #expect(deploy.listener == "user")
        #expect(deploy.updated == Fixture.date("2026-10-31T23:05:03.415Z"))
        #expect(deploy.isWaiting(on: "user"))

        let closed = try #require(st.threads.first { $0.number == 3 })
        #expect(closed.status == "done")
        #expect(closed.listener == nil)
        #expect(closed.waitingSeconds == nil)
        #expect(!closed.isOpen)
        #expect(closed.flow == ["infra", "website"])
    }

    @Test func dutyRecordsDecode() throws {
        let st = try AgentMailJSON.decodeStatus(Fixture.data("status-duty"))
        let rec = try #require(st.duty.first)
        #expect(rec.repo == "website")
        #expect(rec.session == "s1")
        #expect(rec.state == "waiting")
        #expect(rec.onDuty)
        #expect(rec.stale == false)
        #expect(rec.sessionAlive == true)
        #expect(rec.ageSeconds == 0)
        #expect(rec.heartbeat != nil)
    }

    @Test func onCallAndServeDecode() throws {
        let st = try AgentMailJSON.decodeStatus(Data("""
            {"schema": 1, "now": "2026-11-03T05:00:00.000Z", "me": "user", "threads": [], "duty": [], "undelivered": [],
             "oncall": [{"repo": "infra", "dir": "/x/infra", "state": "on-call", "pane": null, "text": "on call"},
                        {"repo": "docs", "dir": "/x/docs", "state": "backing-off", "pane": 41,
                         "until": "2026-11-03T05:40:00.000Z", "reason": "no session came on duty",
                         "text": "backing off until 06:40: no session came on duty"}],
             "serve": {"alive": true, "pid": 4242}}
            """.utf8))
        #expect(st.oncall == [OnCallRepo(repo: "infra", dir: "/x/infra", state: "on-call", text: "on call"),
                              OnCallRepo(repo: "docs", dir: "/x/docs", state: "backing-off", pane: 41,
                                         text: "backing off until 06:40: no session came on duty",
                                         until: Fixture.date("2026-11-03T05:40:00.000Z"),
                                         reason: "no session came on duty")])
        #expect(st.oncall.map(\.isBackingOff) == [false, true])
        #expect(st.serve == ServeInfo(alive: true, pid: 4242))
        #expect(st.onCallRepos == ["infra"], "a repo backing off gets no tab")
        let older = try AgentMailJSON.decodeStatus(Fixture.data("status"))
        #expect(older.oncall.isEmpty, "a CLI from before on call")
        #expect(older.serve == nil)
        #expect(older.onCallRepos.isEmpty)
    }

    @Test func theDirectoryDecodes() throws {
        let st = try AgentMailJSON.decodeStatus(Fixture.data("status-directory"))
        let agents = try #require(st.agents)
        #expect(agents.map(\.name) == ["docs", "gone", "infra", "notes", "website"])
        #expect(agents.map(\.onCall) == [true, true, true, false, true])
        #expect(agents.map(\.trusted) == [false, nil, true, false, true])
        #expect(agents.map(\.dirExists) == [true, false, true, true, true])
        #expect(agents[2] == DirectoryEntry(name: "infra", dir: "/x/infra", summary: "Owns the servers, DNS and deploys.",
                                            trusted: true))
        #expect(agents[2].shownSummary == "Owns the servers, DNS and deploys.")
        #expect(agents[4].borrowed == "README.md")
        #expect(agents[4].shownSummary == "The public website: its pages, build and hosting. (from README.md)")

        let older = try AgentMailJSON.decodeStatus(Fixture.data("status"))
        #expect(older.agents == nil, "a CLI from before the directory: not an empty one")
        let empty = try AgentMailJSON.decodeStatus(Data("""
            {"schema": 1, "now": "2026-11-09T22:44:10.629Z", "me": "user", "threads": [], "duty": [],
             "undelivered": [], "agents": [{"name": "x", "summary": "  ", "borrowed": ""}]}
            """.utf8))
        #expect(empty.agents == [DirectoryEntry(name: "x", onCall: false)], "missing fields read defensively")
    }

    @Test func threadSessionsDecode() throws {
        let st = try AgentMailJSON.decodeStatus(Fixture.data("status-directory"))
        let rotate = try #require(st.threads.first { $0.number == 1 })
        #expect(rotate.session == ThreadSession(id: "1a2b3c4d", name: "📫 infra #1", kind: "background", state: "live"))
        #expect(rotate.session?.isWaitingOnUser == false)
        #expect(rotate.session?.attachCommand == "claude attach 1a2b3c4d")
        let deploy = try #require(st.threads.first { $0.number == 2 })
        let waiting = try #require(deploy.session)
        #expect(waiting.isWaitingOnUser)
        #expect(waiting.waitingSince == Fixture.date("2026-11-09T22:39:04.453Z"))
        #expect(waiting.waitingFor == "permission")
        #expect(waiting.need == "approve Bash: npm run deploy")
        #expect(ThreadSession(id: "a", waitingSince: .now, waitingFor: "permission").need == "permission")
        #expect(ThreadSession(id: "a", waitingSince: .now, needs: " ").need == "input")
        #expect(ThreadSession(id: "a", name: "").shownName == "a")
        #expect(st.threads.filter { $0.session != nil }.map(\.number) == [2, 1], "only the threads serve delivered")
        let older = try AgentMailJSON.decodeStatus(Fixture.data("status"))
        #expect(older.threads.allSatisfy { $0.session == nil })
    }

    @Test func landingDecodes() throws {
        let st = try AgentMailJSON.decodeStatus(Fixture.data("status-directory"))
        let infra = try #require(st.oncall.first { $0.repo == "infra" })
        #expect(infra.cannotLand == "main checkout 2 behind origin/main: uncommitted changes")
        #expect(infra.unlanded == [UnlandedSession(id: "9c0d1e2f", name: "📫 infra #0", branch: "worktree-agent-9c0d",
                                                   commits: 2)])
        #expect(infra.unlanded.first?.text == "“📫 infra #0”: branch worktree-agent-9c0d, 2 commits")
        #expect(UnlandedSession(id: "ab12", branch: "w", commits: 1).text == "“ab12”: branch w, 1 commit")
        #expect(UnlandedSession(id: "ab12", name: "n").text == "“n”: branch unknown", "git couldn't tell")
        let website = try #require(st.oncall.first { $0.repo == "website" })
        #expect(website.cannotLand == nil)
        #expect(website.unlanded.isEmpty)
    }

    @Test func threadFixtureDecodes() throws {
        let th = try AgentMailJSON.decodeThread(Fixture.data("read"))
        #expect(th.id == "2026-10-31-deploy-the-new-site")
        #expect(th.number == 1)
        #expect(th.msgs.count == 2)
        #expect(th.count == 2)
        let first = th.msgs[0], second = th.msgs[1]
        #expect(first.file == "2026-10-31T230503.205Z__website__66ad.md")
        #expect(first.from == "website")
        #expect(first.to == "infra")
        #expect(first.inReplyTo == nil)
        #expect(first.attachments.isEmpty)
        #expect(first.body.hasPrefix("Please deploy **website** v2"))
        #expect(second.inReplyTo == first.file)
        #expect(second.date == Fixture.date("2026-10-31T23:05:03.415Z"))
        #expect(th.participants == ["infra", "user", "website"])
        #expect(th.replyRecipient(me: "user") == "infra")
    }

    @Test func forwardsNameTheirSource() throws {
        let st = try AgentMailJSON.decodeStatus(Fixture.data("status-forward"))
        #expect(st.threads.map(\.forwardedFrom) == ["2026-11-03-rotate-the-certificate",
                                                    "2026-11-03-rotate-the-certificate", nil])
        let th = try AgentMailJSON.decodeThread(Fixture.data("read-forward"))
        #expect(th.forwardedFrom == "2026-11-03-rotate-the-certificate")
        #expect(th.subject == "Fwd: Rotate the certificate")
        #expect(th.msgs.first?.attachments == ["files/chart.png"], "the forwarded files come along")
        #expect(th.msgs.first?.body.hasPrefix("Please document this.\n\n---\n\nForwarded thread #1") == true)
    }

    @Test func threadsBeforeForwardingHaveNoSource() throws {
        let st = try AgentMailJSON.decodeStatus(Fixture.data("status"))
        #expect(st.threads.allSatisfy { $0.forwardedFrom == nil })
        #expect(try AgentMailJSON.decodeThread(Fixture.data("read")).forwardedFrom == nil)
    }

    @Test func unknownSchemaIsRefused() throws {
        var json = try #require(String(data: Fixture.data("status"), encoding: .utf8))
        json = json.replacingOccurrences(of: "\"schema\" : 1", with: "\"schema\" : 2")
        #expect(throws: AgentMailError.schemaMismatch(found: 2, supported: 1)) {
            try AgentMailJSON.decodeStatus(Data(json.utf8))
        }
        #expect(throws: AgentMailError.schemaMismatch(found: nil, supported: 1)) {
            try AgentMailJSON.decodeStatus(Data(#"{"threads": []}"#.utf8))
        }
    }

    @Test func garbageIsADecodeError() {
        do {
            _ = try AgentMailJSON.decodeStatus(Data("agent-mail: oops".utf8))
            Issue.record("expected an error")
        } catch {
            guard case let .decode(what, detail) = error else {
                Issue.record("wrong error \(error)")
                return
            }
            #expect(what == "status --json")
            #expect(detail.contains("not JSON"))
        }
    }

    @Test func emptyStoreStatus() throws {
        let st = try AgentMailJSON.decodeStatus(Data("""
            {"schema": 1, "now": "2026-10-31T23:05:27.069Z", "me": "user", "threads": [], "duty": [], "undelivered": []}
            """.utf8))
        #expect(st.threads.isEmpty)
        #expect(MailboxIndex(status: st).agents.isEmpty)
        #expect(MailboxIndex(status: st).participants.map(\.name) == ["user"])
    }

    @Test func threadWithoutMessagesHasNoOwner() throws {
        let st = try AgentMailJSON.decodeStatus(Data("""
            {"schema": 1, "now": "2026-10-31T23:05:27.069Z", "me": "user", "duty": [], "undelivered": [],
             "threads": [{"id": "x", "number": 9, "subject": "S", "from": "a", "created": "2026-10-31T00:00:00.000Z",
                          "count": 0, "status": "open", "owner": null, "last-from": null, "updated": null,
                          "unread": 0, "unread-by": {}, "participants": [], "waiting-s": null, "listener": null}]}
            """.utf8))
        let t = try #require(st.threads.first)
        #expect(t.owner == nil)
        #expect(t.updated == nil)
        #expect(!t.isWaiting(on: "user"))
        #expect(t.flow.isEmpty, "a status without flow (an older CLI) decodes to none")
        #expect(t.unreadToMe == 0, "nor unread-to-me: the unread count")
    }

    @Test func datesWithAndWithoutMilliseconds() {
        #expect(AgentMailJSON.parseDate("2026-10-31T23:05:03.205Z") != nil)
        #expect(AgentMailJSON.parseDate("2026-10-31T23:05:03Z") != nil)
        #expect(AgentMailJSON.parseDate("yesterday") == nil)
    }

    @Test func errorsReadWell() {
        let e = AgentMailError.exit(command: "reply", code: 1, stderr: "agent-mail: refusing to mail yourself ( user )\n")
        #expect(e.errorDescription == "agent-mail reply failed: refusing to mail yourself ( user )")
        #expect(AgentMailError.schemaMismatch(found: 2, supported: 1).errorDescription?.contains("schema 2") == true)
    }
}
