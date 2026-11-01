// Notifications for new mail: one per new message file (the file name is
// the identifier, the thread id groups them), with an inline Reply and
// Close Thread. Replies go to the CLI and the action completes when it
// exits; a failure is an alert and the text becomes the thread's draft.
import AgentMailKit
import AppKit
import Observation
import UserNotifications

/// What the controller needs from UNUserNotificationCenter; a seam for tests.
protocol NotificationPosting: AnyObject {
    func add(_ request: UNNotificationRequest) async throws
    func removeDeliveredNotifications(withIdentifiers identifiers: [String])
    func setNotificationCategories(_ categories: Set<UNNotificationCategory>)
}

extension UNUserNotificationCenter: NotificationPosting {}

@Observable
final class NotificationController: NSObject, UNUserNotificationCenterDelegate {
    static let messageCategory = "message"
    static let trafficCategory = "traffic"
    static let replyAction = "reply"
    static let closeAction = "close"
    /// More new messages than this at once (e.g. after the app was away)
    /// post only the newest ones.
    static let burstLimit = 5

    private(set) var authorization: UNAuthorizationStatus = .notDetermined
    private(set) var alertStyle: UNAlertStyle = .none

    @ObservationIgnored private let store: MailStore
    @ObservationIgnored private let settings: AppSettings
    @ObservationIgnored private let center: NotificationPosting
    @ObservationIgnored var openThread: (String) -> Void = { _ in }
    @ObservationIgnored var isShowingThread: (String) -> Bool = { _ in false }
    @ObservationIgnored var presentError: (any Error, String?, String?) -> Void = { _, _, _ in }

    init(store: MailStore, settings: AppSettings, center: NotificationPosting = UNUserNotificationCenter.current()) {
        self.store = store
        self.settings = settings
        self.center = center
    }

    func registerCategories() {
        let reply = UNTextInputNotificationAction(identifier: Self.replyAction, title: "Reply", options: [],
                                                  textInputButtonTitle: "Send", textInputPlaceholder: "Reply…")
        let close = UNNotificationAction(identifier: Self.closeAction, title: "Close Thread", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.messageCategory, actions: [reply, close], intentIdentifiers: []),
            // Mail between agents: a click opens it; no replying as the user
            // into someone else's conversation from a banner.
            UNNotificationCategory(identifier: Self.trafficCategory, actions: [], intentIdentifiers: []),
        ])
    }

    func refreshSettings() async {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        authorization = s.authorizationStatus
        alertStyle = s.alertStyle
    }

    @discardableResult
    func requestAuthorization() async -> Bool {
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        await refreshSettings()
        return granted
    }

    /// Opens Agent Mail's page in System Settings › Notifications.
    func openSystemSettings() {
        let id = Bundle.main.bundleIdentifier ?? ""
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(id)") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: Posting

    func post(_ messages: [StoredMessage], index: MailboxIndex) {
        let me = index.me
        let wanted = messages.filter {
            NotificationPolicy.shouldNotify($0, me: me, agentTraffic: settings.notifyAgentTraffic)
        }
        for m in wanted.suffix(Self.burstLimit) {
            let content = UNMutableNotificationContent()
            let from = m.from ?? "agent-mail"
            let toMe = m.to == me
            content.title = toMe ? from : "\(from) → \(m.to ?? "?")"
            content.subtitle = index.thread(m.threadID)?.subject ?? m.threadID
            content.body = MarkdownHTML.plainPreview(m.body, limit: 200)
            content.threadIdentifier = m.threadID
            content.categoryIdentifier = toMe ? Self.messageCategory : Self.trafficCategory
            content.sound = .default
            content.userInfo = ["thread": m.threadID, "from": from, "file": m.file]
            let request = UNNotificationRequest(identifier: m.file, content: content, trigger: nil)
            Task {
                do { try await center.add(request) } catch {
                    Timing.note("notification not posted: \(error.localizedDescription)")
                }
            }
        }
    }

    /// The thread was read, replied to or closed: its banners are stale.
    func removeDelivered(for thread: ThreadDetail) {
        center.removeDeliveredNotifications(withIdentifiers: thread.msgs.map(\.file))
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let thread = notification.request.content.threadIdentifier
        let showing = await MainActor.run { isShowingThread(thread) }
        return showing ? [] : [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse) async {
        let content = response.notification.request.content
        let thread = content.userInfo["thread"] as? String ?? content.threadIdentifier
        let from = content.userInfo["from"] as? String
        let action = response.actionIdentifier
        let text = (response as? UNTextInputNotificationResponse)?.userText
        await handle(action: action, thread: thread, from: from, text: text)
    }

    func handle(action: String, thread: String, from: String?, text: String?) async {
        switch action {
        case UNNotificationDefaultActionIdentifier:
            openThread(thread)
        case Self.replyAction:
            guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            let me = store.index.me
            let to = (from.flatMap { $0 == me ? nil : $0 }) ?? store.index.thread(thread)?.replyRecipient(me: me)
            guard let to else {
                presentError(AgentMailError.exit(command: "reply", code: 1, stderr: "nobody to reply to"), thread, text)
                return
            }
            do {
                try await store.reply(threadID: thread, to: to, body: text)
            } catch {
                presentError(error, thread, text)
            }
        case Self.closeAction:
            do {
                try await store.close(threadID: thread)
            } catch {
                presentError(error, thread, nil)
            }
        default:
            break
        }
    }
}

/// The first launch asks for notification permission in context, with a
/// word on why, instead of a bare system prompt.
enum FirstRun {
    static func offerNotifications(over window: NSWindow?, notifications: NotificationController) async {
        await notifications.refreshSettings()
        guard notifications.authorization == .notDetermined else { return }
        let alert = NSAlert()
        alert.messageText = "Get notified when an agent writes to you?"
        alert.informativeText = """
            Agent Mail can show a notification when one of your agents sends you a message, \
            and you can reply or close the thread right from it.
            """
        alert.addButton(withTitle: "Allow Notifications…")
        alert.addButton(withTitle: "Not Now")
        guard await run(alert, over: window) == .alertFirstButtonReturn else { return }
        guard await notifications.requestAuthorization() else { return }
        if notifications.alertStyle != .alert {
            await offerAlertStyle(over: window, notifications: notifications)
        }
    }

    /// Inline Reply needs the Alerts style; banners vanish before you can type.
    static func offerAlertStyle(over window: NSWindow?, notifications: NotificationController) async {
        let alert = NSAlert()
        alert.messageText = "Choose the Alerts style to reply from notifications"
        alert.informativeText = """
            Banners disappear before you can type a reply. In System Settings › Notifications › \
            Agent Mail, set the alert style to Alerts so notifications stay until you act on them.
            """
        alert.addButton(withTitle: "Open System Settings")
        alert.addButton(withTitle: "Later")
        if await run(alert, over: window) == .alertFirstButtonReturn {
            notifications.openSystemSettings()
        }
    }

    static func run(_ alert: NSAlert, over window: NSWindow?) async -> NSApplication.ModalResponse {
        guard let window, window.isVisible else { return alert.runModal() }
        return await withCheckedContinuation { cont in
            alert.beginSheetModal(for: window) { cont.resume(returning: $0) }
        }
    }
}
