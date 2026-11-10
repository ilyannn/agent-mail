// Wires the app together: menus, the notification delegate (before launch
// finishes, so a Reply that launched the app isn't lost), the status item,
// the store, and the windows. App-wide commands land here when no window
// handles them.
import AgentMailKit
import AppKit
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate, MailActions, NSMenuItemValidation {
    let settings = AppSettings.shared
    private(set) lazy var store = MailStore(settings: settings, location: StoreLocation.resolve(settings: settings))
    private(set) lazy var notifications = NotificationController(store: store, settings: settings)
    private(set) lazy var dock = DockPolicyController(settings: settings)
    private var statusItem: StatusItemController?
    private var mainWindow: MainWindowController?
    private var settingsWindow: SettingsWindowController?
    private var terminationSignal: (any DispatchSourceSignal)?
    #if DEBUG
    private var debugHooks: DebugHooks?
    var debugMain: MainWindowController? { mainWindow }
    var debugStatusTitle: String { statusItem?.debugTitle ?? "" }
    func debugStatusMenu() -> String { statusItem?.debugMenuDump() ?? "" }
    #endif

    /// Hosting the unit tests (AgentMailTests): start nothing, so no store,
    /// status item, window or notification delegate is touched.
    static let isHostingTests = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    func applicationWillFinishLaunching(_ notification: Notification) {
        guard !Self.isHostingTests else { return }
        UNUserNotificationCenter.current().delegate = notifications
        notifications.registerCategories()
        notifications.openThread = { [weak self] id in self?.openThread(id) }
        notifications.isShowingThread = { [weak self] id in self?.isShowingThread(id) ?? false }
        notifications.presentError = { [weak self] error, thread, draft in
            self?.presentError(error, thread: thread, draft: draft)
        }
        NSApp.mainMenu = MainMenu.build()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        guard !Self.isHostingTests else { return }
        store.onNewMessages = { [weak self] messages in
            guard let self else { return }
            notifications.post(messages, index: store.index)
        }
        store.onThreadRead = { [weak self] thread in self?.notifications.removeDelivered(for: thread) }
        statusItem = StatusItemController(store: store, actions: self)
        quitOnSIGTERM()
        #if DEBUG
        debugHooks = DebugHooks.installIfRequested(app: self)
        #endif
        store.start()
        Task { await notifications.refreshSettings() }

        let launchedByNotification = notification.userInfo?[NSApplication.launchUserNotificationUserInfoKey] != nil
        launched = true
        if let id = pendingThread {
            pendingThread = nil
            openThread(id)
        } else if settings.mainWindowWasOpen || !settings.firstRunDone, !launchedByNotification {
            showMainWindow(nil)
        }
        dock.update()
        if !settings.firstRunDone {
            settings.firstRunDone = true
            Task { await FirstRun.offerNotifications(over: mainWindow?.window, notifications: notifications) }
        }
    }

    private var launched = false
    /// A thread a URL asked for while the app was still launching.
    private var pendingThread: String?

    /// `agent-mail:///thread/<id>` (AppURL), from `agent-mail open`: shows
    /// the thread. A URL that launched the app arrives before launching
    /// finishes, so the thread waits for it.
    func application(_ application: NSApplication, open urls: [URL]) {
        guard !Self.isHostingTests, let id = urls.compactMap(AppURL.threadID(in:)).last else { return }
        if launched { openThread(id) } else { pendingThread = id }
    }

    /// `just install` replaces a running copy with SIGTERM (no Apple Events,
    /// so no automation prompt); quit the normal way so state is saved.
    private func quitOnSIGTERM() {
        signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        source.setEventHandler {
            MainActor.assumeIsolated { NSApp.terminate(nil) }
        }
        source.resume()
        terminationSignal = source
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        // The menu bar extra may be hidden; reopening always shows the window.
        if !flag { showMainWindow(nil) }
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool { true }

    func applicationDidBecomeActive(_ notification: Notification) {
        guard !Self.isHostingTests else { return }
        Task { await notifications.refreshSettings() }
    }

    /// Whether the window was open, recorded before quitting closes it.
    private var terminating = false

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if !Self.isHostingTests {
            settings.mainWindowWasOpen = mainWindow?.window?.isVisible ?? false
        }
        terminating = true
        return .terminateNow
    }

    // MARK: Windows

    private func mainWindowController() -> MainWindowController {
        if let mainWindow { return mainWindow }
        let wc = MainWindowController(store: store)
        wc.onClose = { [weak self] in
            guard let self, !terminating else { return }
            settings.mainWindowWasOpen = false
        }
        mainWindow = wc
        return wc
    }

    private func bringForward(_ window: NSWindow?) {
        dock.prepareToShowWindow()
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    @objc func showMainWindow(_ sender: Any?) {
        let wc = mainWindowController()
        wc.showWindow(nil)
        bringForward(wc.window)
        settings.mainWindowWasOpen = true
    }

    func openThread(_ id: String) {
        showMainWindow(nil)
        mainWindow?.reveal(threadID: id)
    }

    func open(mailbox: Mailbox) {
        showMainWindow(nil)
        store.mailbox = mailbox
    }

    private func isShowingThread(_ id: String) -> Bool {
        guard NSApp.isActive, let w = mainWindow?.window, w.isKeyWindow else { return false }
        return store.selectedThreadID == id
    }

    @objc func showSettings(_ sender: Any?) {
        if settingsWindow == nil {
            settingsWindow = SettingsWindowController(store: store, notifications: notifications, dock: dock,
                                                      settings: settings)
        }
        settingsWindow?.showWindow(nil)
        bringForward(settingsWindow?.window)
    }

    /// New Message (⌘N, the toolbar, the menu bar extra): the main window
    /// comes forward with the draft, an empty thread and its reply box.
    @objc func newMessage(_ sender: Any?) {
        showMainWindow(nil)
        mainWindow?.newMessage()
    }

    /// File › Add Folder… while the main window isn't key: the window comes
    /// forward and asks for the folder.
    @objc func addFolder(_ sender: Any?) {
        showMainWindow(nil)
        mainWindow?.addFolder()
    }

    @objc func refresh(_ sender: Any?) {
        store.refresh(reason: "menu")
    }

    @objc func showInbox(_ sender: Any?) { open(mailbox: .inbox) }
    @objc func showUndelivered(_ sender: Any?) { open(mailbox: .undelivered) }
    @objc func showAllMail(_ sender: Any?) { open(mailbox: .allMail) }

    /// Sort By while the main window isn't key: the order is the store's, so
    /// the window shows it when it opens.
    @objc func setThreadOrder(_ sender: Any?) {
        if let order = ThreadOrder(menuItem: sender) { store.threadOrder = order }
    }

    /// Participant Filter while the main window isn't key, likewise.
    @objc func setParticipantFilter(_ sender: Any?) {
        if let filter = ParticipantFilter(menuItem: sender) { store.participantFilter = filter }
    }

    // Thread and text size commands reach the app delegate only when the
    // main window isn't key; they are disabled then.
    @objc func replyToThread(_ sender: Any?) {}
    @objc func forwardThread(_ sender: Any?) {}
    @objc func sendReply(_ sender: Any?) {}
    @objc func attachFiles(_ sender: Any?) {}
    @objc func closeThread(_ sender: Any?) {}
    @objc func copyThreadID(_ sender: Any?) {}
    @objc func revealThreadInFinder(_ sender: Any?) {}
    @objc func increaseTextSize(_ sender: Any?) {}
    @objc func decreaseTextSize(_ sender: Any?) {}
    @objc func resetTextSize(_ sender: Any?) {}

    /// The docs: the README of the agent-mail checkout the CLI runs from,
    /// else its agent-mail skill, which documents every command.
    @objc func showHelp(_ sender: Any?) {
        let repo = store.cliURL.map { $0.resolvingSymlinksInPath().deletingLastPathComponent().deletingLastPathComponent() }
        let candidates = [repo?.appending(path: "README.md"), repo?.appending(path: "skills/agent-mail/SKILL.md")]
        if let doc = candidates.compactMap({ $0 }).first(where: { FileManager.default.fileExists(atPath: $0.path) }) {
            NSWorkspace.shared.open(doc)
            return
        }
        let alert = NSAlert()
        alert.messageText = "Agent Mail Help"
        alert.informativeText = "Run `agent-mail help` in Terminal for every command. The app shows the README of the agent-mail checkout once it has found the command-line tool."
        alert.runModal()
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        switch menuItem.action {
        case #selector(setThreadOrder(_:)):
            menuItem.state = ThreadOrder(menuItem: menuItem) == store.threadOrder ? .on : .off
            return true
        case #selector(setParticipantFilter(_:)):
            menuItem.state = ParticipantFilter(menuItem: menuItem) == store.participantFilter ? .on : .off
            if case .agent = store.mailbox { return true }
            return false
        case #selector(replyToThread(_:)), #selector(forwardThread(_:)), #selector(sendReply(_:)), #selector(closeThread(_:)),
             #selector(attachFiles(_:)), #selector(copyThreadID(_:)), #selector(revealThreadInFinder(_:)),
             #selector(increaseTextSize(_:)), #selector(decreaseTextSize(_:)), #selector(resetTextSize(_:)):
            return false
        default:
            return true
        }
    }

    // MARK: Errors

    /// A failed write: shown as an alert (on the main window when it's
    /// open), never as a notification. A reply typed in a notification is
    /// kept as the thread's draft.
    func presentError(_ error: any Error, thread: String?, draft: String?) {
        if let thread {
            if let draft { store.drafts[thread] = draft }
            openThread(thread)
            if let draft { mainWindow?.restoreDraft(draft, for: thread) }
        }
        if let w = mainWindow?.window, w.isVisible {
            w.presentError(error, modalFor: w, delegate: nil, didPresent: nil, contextInfo: nil)
        } else {
            NSApp.activate()
            NSApp.presentError(error)
        }
    }
}
