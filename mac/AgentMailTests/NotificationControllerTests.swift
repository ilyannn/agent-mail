// The notification handler through its seam: a fake notification center and
// a stub CLI (a shell script that logs its arguments and stdin).
import AgentMailKit
import Foundation
import Testing
import UserNotifications
@testable import AgentMail

final class FakeCenter: NotificationPosting {
    var added: [UNNotificationRequest] = []
    var removed: [[String]] = []
    var categories: Set<UNNotificationCategory> = []

    func add(_ request: UNNotificationRequest) async throws { added.append(request) }
    func removeDeliveredNotifications(withIdentifiers identifiers: [String]) { removed.append(identifiers) }
    func setNotificationCategories(_ categories: Set<UNNotificationCategory>) { self.categories = categories }
}

/// A temporary directory with a stub `agent-mail`, its own defaults suite
/// and a store path nothing reads.
final class Harness {
    let dir: URL
    let suite: String
    let defaults: UserDefaults
    let settings: AppSettings
    let store: MailStore
    let center = FakeCenter()
    let controller: NotificationController
    var errors: [(String, String?, String?)] = []
    var opened: [String] = []

    init(exitCode: Int = 0) throws {
        dir = FileManager.default.temporaryDirectory.appending(path: "agent-mail-app-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let cli = dir.appending(path: "agent-mail")
        try """
            #!/bin/sh
            echo "$*" >> "\(dir.path)/calls.log"
            cat >> "\(dir.path)/stdin.log"
            [ \(exitCode) = 0 ] || { echo "agent-mail: no thread t1" >&2; exit \(exitCode); }
            echo t1
            """.write(to: cli, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: cli.path)
        suite = "agent-mail-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        settings = AppSettings(defaults: defaults)
        settings.cliPath = cli.path
        store = MailStore(settings: settings,
                          location: StoreLocation(url: dir.appending(path: "store"), source: .environment))
        controller = NotificationController(store: store, settings: settings, center: center)
        controller.presentError = { [unowned self] error, thread, draft in
            errors.append(((error as? AgentMailError)?.errorDescription ?? "\(error)", thread, draft))
        }
        controller.openThread = { [unowned self] id in opened.append(id) }
    }

    deinit {
        try? FileManager.default.removeItem(at: dir)
        UserDefaults.standard.removePersistentDomain(forName: suite)
    }

    var calls: String { (try? String(contentsOf: dir.appending(path: "calls.log"), encoding: .utf8)) ?? "" }
    var stdin: String { (try? String(contentsOf: dir.appending(path: "stdin.log"), encoding: .utf8)) ?? "" }

    /// Waits for the posting tasks to run.
    func waitForPosts(_ n: Int) async {
        for _ in 0..<200 where center.added.count < n { await Task.yield() }
    }
}

func message(_ file: String, from: String, to: String, thread: String = "t1", body: String = "Hello **there**") -> StoredMessage {
    StoredMessage(threadID: thread, file: file, from: from, to: to, date: nil, status: nil, body: body)
}

@MainActor @Suite struct NotificationControllerTests {
    @Test func categoriesOfferReplyAndCloseForMailToTheUser() throws {
        let h = try Harness()
        h.controller.registerCategories()
        let message = try #require(h.center.categories.first { $0.identifier == NotificationController.messageCategory })
        #expect(message.actions.map(\.identifier) == ["reply", "close"])
        #expect(message.actions.first is UNTextInputNotificationAction)
        let traffic = try #require(h.center.categories.first { $0.identifier == NotificationController.trafficCategory })
        #expect(traffic.actions.isEmpty)
    }

    @Test func mailToTheUserNotifiesAgentTrafficOnlyWhenAskedFor() async throws {
        let h = try Harness()
        let index = MailboxIndex(status: MailStatus(now: .now, me: "user", threads: [
            ThreadSummary(id: "t1", subject: "Deploy the new site"),
        ]))
        let messages = [message("a.md", from: "infra", to: "user"), message("b.md", from: "infra", to: "website")]
        h.controller.post(messages, index: index)
        await h.waitForPosts(1)
        #expect(h.center.added.map(\.identifier) == ["a.md"])
        let content = try #require(h.center.added.first?.content)
        #expect(content.title == "infra")
        #expect(content.subtitle == "Deploy the new site")
        #expect(content.body == "Hello there")
        #expect(content.threadIdentifier == "t1")
        #expect(content.categoryIdentifier == NotificationController.messageCategory)

        h.settings.notifyAgentTraffic = true
        h.controller.post([message("c.md", from: "infra", to: "website")], index: index)
        await h.waitForPosts(2)
        #expect(h.center.added.last?.content.title == "infra → website")
        #expect(h.center.added.last?.content.categoryIdentifier == NotificationController.trafficCategory)
    }

    @Test func eachKindPlaysItsOwnSound() async throws {
        let h = try Harness()
        #expect(h.settings.messageSound == .systemDefault)
        h.controller.post([message("a.md", from: "infra", to: "user")], index: .empty)
        await h.waitForPosts(1)
        #expect(h.center.added.last?.content.sound != nil)

        h.settings.messageSound = .none
        h.controller.post([message("b.md", from: "infra", to: "user")], index: .empty)
        await h.waitForPosts(2)
        let toMe = try #require(h.center.added.last)
        #expect(toMe.identifier == "b.md")
        #expect(toMe.content.sound == nil)

        h.settings.notifyAgentTraffic = true
        h.settings.trafficSound = .bundled("Prompt")
        h.controller.post([message("c.md", from: "infra", to: "website")], index: .empty)
        await h.waitForPosts(3)
        let traffic = try #require(h.center.added.last)
        #expect(traffic.identifier == "c.md")
        #expect(traffic.content.categoryIdentifier == NotificationController.trafficCategory)
        #expect(traffic.content.sound != nil)
    }

    @Test func aBurstPostsOnlyTheNewest() async throws {
        let h = try Harness()
        let messages = (1...7).map { message("\($0).md", from: "infra", to: "user") }
        h.controller.post(messages, index: .empty)
        await h.waitForPosts(5)
        for _ in 0..<50 { await Task.yield() }
        #expect(h.center.added.map(\.identifier) == ["3.md", "4.md", "5.md", "6.md", "7.md"])
    }

    @Test func myOwnMessagesNeverNotify() async throws {
        let h = try Harness()
        h.settings.notifyAgentTraffic = true
        h.controller.post([message("a.md", from: "user", to: "infra")], index: .empty)
        for _ in 0..<50 { await Task.yield() }
        #expect(h.center.added.isEmpty)
    }

    @Test func inlineReplyRunsTheCLIAndCompletesWhenItExits() async throws {
        let h = try Harness()
        await h.controller.handle(action: NotificationController.replyAction, thread: "t1", from: "infra",
                                  text: "Yes, after 18:00")
        #expect(h.calls == "reply t1 --to infra\n")
        #expect(h.stdin == "Yes, after 18:00")
        #expect(h.errors.isEmpty)
    }

    @Test func aFailedReplyIsAnAlertAndKeepsTheText() async throws {
        let h = try Harness(exitCode: 1)
        await h.controller.handle(action: NotificationController.replyAction, thread: "t1", from: "infra",
                                  text: "Keep me")
        #expect(h.errors.count == 1)
        #expect(h.errors.first?.0 == "agent-mail reply failed: no thread t1")
        #expect(h.errors.first?.1 == "t1")
        #expect(h.errors.first?.2 == "Keep me")
    }

    @Test func blankRepliesSendNothing() async throws {
        let h = try Harness()
        await h.controller.handle(action: NotificationController.replyAction, thread: "t1", from: "infra", text: "  \n")
        #expect(h.calls.isEmpty)
    }

    @Test func closeThreadRunsClose() async throws {
        let h = try Harness()
        await h.controller.handle(action: NotificationController.closeAction, thread: "t1", from: "infra", text: nil)
        #expect(h.calls == "close t1\n")
    }

    @Test func clickingOpensTheThread() async throws {
        let h = try Harness()
        await h.controller.handle(action: UNNotificationDefaultActionIdentifier, thread: "t1", from: "infra", text: nil)
        #expect(h.opened == ["t1"])
        #expect(h.calls.isEmpty)
    }

    @Test func readingAThreadRemovesItsBanners() throws {
        let h = try Harness()
        h.controller.removeDelivered(for: ThreadDetail(id: "t1", subject: "S", msgs: [
            Message(file: "a.md", from: "infra", to: "user", date: nil, body: "x"),
            Message(file: "b.md", from: "user", to: "infra", date: nil, body: "y"),
        ]))
        #expect(h.center.removed == [["a.md", "b.md"]])
    }
}
