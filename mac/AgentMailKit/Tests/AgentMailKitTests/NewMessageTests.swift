import Foundation
import Testing
@testable import AgentMailKit

@Suite struct NewMessageTests {
    func thread(_ id: String, count: Int, updated: Double = 0) -> ThreadSummary {
        ThreadSummary(id: id, subject: id, count: count, updated: Date(timeIntervalSince1970: updated))
    }

    @Test func firstRunIsSilent() {
        var tracker = NewMessageTracker(saved: nil)
        let files = ["t1": ["a.md", "b.md"]]
        #expect(tracker.update(threads: [thread("t1", count: 2)]) { files[$0] ?? [] }.isEmpty)
        #expect(tracker.primed)
        #expect(tracker.seen == ["t1/a.md", "t1/b.md"])
    }

    @Test func newFilesAreReportedOnce() {
        var tracker = NewMessageTracker(saved: nil)
        var files = ["t1": ["a.md"]]
        _ = tracker.update(threads: [thread("t1", count: 1)]) { files[$0] ?? [] }
        files["t1"] = ["a.md", "b.md"]
        files["t2"] = ["c.md"]
        let fresh = tracker.update(threads: [thread("t1", count: 2, updated: 1), thread("t2", count: 1)]) {
            files[$0] ?? []
        }
        #expect(fresh == [MessageRef(threadID: "t1", file: "b.md"), MessageRef(threadID: "t2", file: "c.md")])
        let again = tracker.update(threads: [thread("t1", count: 2, updated: 1), thread("t2", count: 1)]) {
            files[$0] ?? []
        }
        #expect(again.isEmpty)
    }

    @Test func unchangedThreadsAreNotListed() {
        var tracker = NewMessageTracker(saved: nil)
        var listed: [String] = []
        let threads = [thread("t1", count: 1), thread("t2", count: 1)]
        _ = tracker.update(threads: threads) { listed.append($0); return ["x.md"] }
        listed = []
        _ = tracker.update(threads: [thread("t1", count: 2, updated: 5), threads[1]]) { listed.append($0); return ["x.md"] }
        #expect(listed == ["t1"])
    }

    @Test func savedSetCatchesUpOnMessagesWhileAway() {
        var tracker = NewMessageTracker(saved: ["t1/a.md"])
        let fresh = tracker.update(threads: [thread("t1", count: 2)]) { _ in ["a.md", "b.md"] }
        #expect(fresh == [MessageRef(threadID: "t1", file: "b.md")])
    }

    @Test func goneThreadsAreForgotten() {
        var tracker = NewMessageTracker(saved: ["gone/a.md", "t1/a.md"])
        _ = tracker.update(threads: [thread("t1", count: 1)]) { _ in ["a.md"] }
        #expect(tracker.seen == ["t1/a.md"])
    }

    @Test func policy() {
        func m(_ from: String, _ to: String) -> StoredMessage {
            StoredMessage(threadID: "t", file: "f", from: from, to: to, date: nil, status: nil, body: "")
        }
        #expect(NotificationPolicy.shouldNotify(m("infra", "user"), me: "user", agentTraffic: false))
        #expect(!NotificationPolicy.shouldNotify(m("infra", "website"), me: "user", agentTraffic: false))
        #expect(NotificationPolicy.shouldNotify(m("infra", "website"), me: "user", agentTraffic: true))
        #expect(!NotificationPolicy.shouldNotify(m("user", "infra"), me: "user", agentTraffic: true))
    }

    @Test func seenSetFileRoundTrips() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "seen-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let a = SeenSetFile(directory: dir, store: URL(filePath: "/tmp/store-a"))
        let b = SeenSetFile(directory: dir, store: URL(filePath: "/tmp/store-b"))
        #expect(a.url != b.url)
        #expect(a.load() == nil)
        try a.save(["t/x.md", "t/y.md"])
        #expect(a.load() == ["t/x.md", "t/y.md"])
        #expect(b.load() == nil)
    }
}

@Suite struct StoreReaderTests {
    @Test func readsMessageFilesWithoutWriting() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "store-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let msgs = root.appending(path: "threads/t1/msgs")
        try FileManager.default.createDirectory(at: msgs, withIntermediateDirectories: true)
        let text = """
            ---
            from: "infra"
            to: "user"
            date: "2026-10-31T23:05:03.415Z"
            in-reply-to: "2026-10-31T230503.205Z__website__66ad.md"
            attachments: ["files/a.txt", "files/b c.txt"]
            note: "say \\"hi\\"\\nthere"
            ---

            Can I restart nginx?

            """
        try text.write(to: msgs.appending(path: "2026-10-31T230503.415Z__infra__872a.md"), atomically: true, encoding: .utf8)
        try "x".write(to: msgs.appending(path: ".2026-10-31T230504.000Z__infra__aaaa.md.tmp"), atomically: true, encoding: .utf8)

        let reader = StoreReader(root: root)
        #expect(reader.messageFiles(thread: "t1") == ["2026-10-31T230503.415Z__infra__872a.md"])
        #expect(reader.messageFiles(thread: "missing").isEmpty)
        let m = try #require(reader.message(thread: "t1", file: "2026-10-31T230503.415Z__infra__872a.md"))
        #expect(m.from == "infra")
        #expect(m.to == "user")
        #expect(m.date == Fixture.date("2026-10-31T23:05:03.415Z"))
        #expect(m.body == "Can I restart nginx?")
        let (fields, _) = Frontmatter.split(text)
        #expect(fields["note"] == "say \"hi\"\nthere")
    }

    @Test func noFrontmatterIsAllBody() {
        let (fields, body) = Frontmatter.split("just text\n")
        #expect(fields.isEmpty)
        #expect(body == "just text\n")
    }
}
