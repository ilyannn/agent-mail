// The new-thread draft: what may be sent, what counts as typed, the draft
// row's words and the forward header's line.
import Foundation
import Testing
@testable import AgentMailKit

@Suite struct DraftTests {
    @Test func aNewMessageNeedsARecipientAndABody() {
        var d = NewThreadDraft()
        #expect(!d.canSend(me: "user"))
        d.to = "infra"
        #expect(!d.canSend(me: "user"), "no body")
        d.body = " \n "
        #expect(!d.canSend(me: "user"), "a blank body")
        d.body = "Hello"
        #expect(d.canSend(me: "user"))
        d.to = " infra "
        #expect(d.recipient == "infra")
        #expect(d.canSend(me: "user"), "spaces around the name don't count")
        d.to = "in fra"
        #expect(!d.canSend(me: "user"))
        d.to = "user"
        #expect(!d.canSend(me: "user"), "not to myself")
    }

    @Test func aForwardsNoteIsOptional() {
        var d = NewThreadDraft.forwarding(ForwardSource(threadID: "t"), subject: "Deploy")
        #expect(d.subject == "Fwd: Deploy")
        #expect(!d.canSend(me: "user"), "no recipient yet")
        d.to = "docs"
        #expect(d.canSend(me: "user"))
        #expect(NewThreadDraft.forwarding(ForwardSource(threadID: "t"), subject: "Fwd: Deploy").subject == "Fwd: Deploy")
    }

    @Test func onlyWhatWasTypedIsWorthAskingAbout() {
        var d = NewThreadDraft(to: "infra")
        #expect(!d.hasTypedContent, "the app filled in To")
        d.to = "docs"
        #expect(d.hasTypedContent)
        d.to = ""
        #expect(!d.hasTypedContent, "clearing a field loses nothing")
        d.subject = "Hi"
        #expect(d.hasTypedContent)
        d.subject = ""
        d.body = "x"
        #expect(d.hasTypedContent)

        var f = NewThreadDraft.forwarding(ForwardSource(threadID: "t", message: "m.md"), subject: "Deploy")
        #expect(!f.hasTypedContent, "the subject was filled in")
        f.subject = "Fwd: Deploy, please read"
        #expect(f.hasTypedContent)
    }

    @Test func newMessageAgainFillsAnEmptyRecipient() {
        var d = NewThreadDraft()
        d.fillRecipient("infra")
        #expect(d.to == "infra")
        #expect(!d.hasTypedContent, "as if it had been there from the start")
        d.fillRecipient("docs")
        #expect(d.to == "infra", "never over a recipient")
        var typed = NewThreadDraft()
        typed.to = "website"
        typed.fillRecipient("infra")
        #expect(typed.to == "website")
    }

    @Test func theDraftRowSaysWhatAndToWhom() {
        var d = NewThreadDraft()
        #expect(d.rowTitle == "New Message")
        #expect(d.rowDetail == "Draft")
        d.to = "infra"
        d.subject = "  Deploy  "
        #expect(d.rowTitle == "Deploy")
        #expect(d.rowDetail == "to infra")
        d.to = "in fra"
        #expect(d.rowDetail == "Draft")
        var f = NewThreadDraft(forward: ForwardSource(threadID: "t"))
        #expect(f.rowTitle == "Forward")
        f.subject = "Fwd: X"
        #expect(f.rowTitle == "Fwd: X")
        #expect(NewThreadDraft().id != NewThreadDraft().id)
    }

    @Test func theHeaderSaysWhatGoesAlong() throws {
        let th = try AgentMailJSON.decodeThread(Fixture.data("read"))
        let date: (Date) -> String = { _ in "3 Nov, 10:02" }
        func say(_ message: String?, thread: ThreadDetail? = nil, summary: ThreadSummary? = nil) -> String {
            Forwarding.description(of: ForwardSource(threadID: th.id, message: message), thread: thread,
                                   summary: summary, me: "user", formatDate: date)
        }
        #expect(say(nil, thread: th) == "Forwarding #1, all 2 messages, after your note")
        #expect(say("2026-10-31T230503.415Z__infra__872a.md", thread: th)
            == "Forwarding infra’s message of 3 Nov, 10:02 from #1")
        #expect(say("nope.md", thread: th) == "Forwarding a message from #1")

        let files = ThreadDetail(id: "t", number: 12, subject: "S", msgs: [
            Message(file: "a.md", from: "user", to: "infra", date: nil, attachments: ["files/x.png", "files/y.md"],
                    body: "a"),
            Message(file: "b.md", from: "infra", to: "user", date: nil, body: "b"),
            Message(file: "c.md", from: "infra", to: "user", date: nil, attachments: ["files/z.png"], body: "c"),
        ])
        #expect(say(nil, thread: files) == "Forwarding #12, all 3 messages and their attachments, after your note")
        #expect(say("a.md", thread: files) == "Forwarding your message from #12, with its 2 attachments")
        #expect(say("c.md", thread: files) == "Forwarding infra’s message from #12, with its attachment")
        let single = ThreadDetail(id: "t", number: 5, subject: "S", msgs: [files.msgs[2]])
        #expect(say(nil, thread: single) == "Forwarding #5, its one message and its attachments, after your note")

        // Before the thread loads: the summary's number and count.
        let summary = ThreadSummary(id: "t", number: 9, subject: "S", count: 4)
        #expect(say(nil, summary: summary) == "Forwarding #9, all 4 messages, after your note")
        #expect(say("a.md", summary: summary) == "Forwarding a message from #9")
        #expect(say(nil, summary: ThreadSummary(id: "t", subject: "Plan", count: 0)) == "Forwarding “Plan”, after your note")
        #expect(say(nil) == "Forwarding the thread, after your note")
    }

    @Test func recipientsAreValidNamesOtherThanMe() {
        #expect(Formatting.isValidRecipient("infra", me: "user"))
        #expect(!Formatting.isValidRecipient("user", me: "user"))
        #expect(!Formatting.isValidRecipient("", me: "user"))
        #expect(!Formatting.isValidRecipient("a b", me: "user"))
    }
}
