// The main window: sidebar, thread list and thread view in a split view
// controller, a unified toolbar whose sections track the split dividers, and
// the thread commands (Reply, Send, Close Thread, mailboxes) for the menus
// and toolbar. Closing it leaves the app running in the menu bar.
import AgentMailKit
import AppKit
import Observation

extension NSToolbarItem.Identifier {
    static let newMessage = Self("newMessage")
    static let refresh = Self("refresh")
    static let listTrackingSeparator = Self("listTrackingSeparator")
    static let reply = Self("reply")
    static let closeThread = Self("closeThread")
}

final class MainWindowController: NSWindowController, NSWindowDelegate, NSToolbarDelegate, NSMenuItemValidation,
    NSToolbarItemValidation {
    private let store: MailStore
    private let split = NSSplitViewController()
    let sidebar: SidebarViewController
    let list: ThreadListViewController
    let thread: ThreadViewController
    private var observation: Task<Void, Never>?
    var onClose: () -> Void = {}

    init(store: MailStore) {
        self.store = store
        sidebar = SidebarViewController(store: store)
        list = ThreadListViewController(store: store)
        thread = ThreadViewController(store: store)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1120, height: 720),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                              backing: .buffered, defer: false)
        window.identifier = NSUserInterfaceItemIdentifier("main")
        window.title = "Agent Mail"
        window.minSize = NSSize(width: 760, height: 420)
        window.tabbingMode = .disallowed
        window.isReleasedWhenClosed = false
        window.autorecalculatesKeyViewLoop = true
        super.init(window: window)

        let sidebarItem = NSSplitViewItem(sidebarWithViewController: sidebar)
        sidebarItem.minimumThickness = 150
        sidebarItem.maximumThickness = 320
        let listItem = NSSplitViewItem(contentListWithViewController: list)
        listItem.minimumThickness = 240
        listItem.maximumThickness = 520
        let detailItem = NSSplitViewItem(viewController: thread)
        detailItem.minimumThickness = 360
        split.addSplitViewItem(sidebarItem)
        split.addSplitViewItem(listItem)
        split.addSplitViewItem(detailItem)
        split.splitView.autosaveName = "MainSplit"
        window.contentViewController = split

        let toolbar = NSToolbar(identifier: "MainToolbar")
        toolbar.delegate = self
        toolbar.displayMode = .iconOnly
        toolbar.allowsUserCustomization = true
        toolbar.autosavesConfiguration = true
        window.toolbar = toolbar
        window.toolbarStyle = .unified

        window.setContentSize(NSSize(width: 1120, height: 720))
        if !window.setFrameUsingName("MainWindow") { window.center() }
        window.setFrameAutosaveName("MainWindow")
        window.delegate = self

        list.onReturn = { [weak self] in self?.thread.focusReply() }
        list.onSpace = { [weak self] in self?.thread.pageDown() }
        list.onContextReply = { [weak self] id in
            self?.store.select(threadID: id)
            self?.thread.focusReply()
        }
        list.onContextClose = { [weak self] id in
            self?.store.select(threadID: id)
            self?.thread.closeThread()
        }
        store.detailIsVisible = { [weak self] in self?.isThreadVisible ?? false }

        updateTitle()
        let changes = Observations { [store] in (store.mailbox, store.index) }
        observation = Task { [weak self] in
            for await _ in changes { self?.updateTitle() }
        }
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private var isThreadVisible: Bool {
        guard let window, window.isVisible, window.occlusionState.contains(.visible) else { return false }
        return !(split.splitViewItems.last?.isCollapsed ?? true)
    }

    private func updateTitle() {
        let threads = store.index.threads(in: store.mailbox)
        window?.title = store.mailbox.title
        let unread = threads.reduce(0) { $0 + $1.unread }
        var parts = ["\(threads.count) thread\(threads.count == 1 ? "" : "s")"]
        if unread > 0 { parts.append("\(unread) unread") }
        window?.subtitle = parts.joined(separator: ", ")
    }

    /// Shows a thread: switches to a mailbox that holds it when the current
    /// one doesn't, then selects it.
    func reveal(threadID id: String) {
        let inCurrent = store.index.threads(in: store.mailbox).contains { $0.id == id }
        if !inCurrent { store.mailbox = store.index.preferredMailbox(for: id) }
        store.select(threadID: id)
        list.reveal(id)
    }

    func restoreDraft(_ text: String, for id: String) {
        thread.restoreDraft(text, for: id)
    }

    #if DEBUG
    func debugSelect(_ id: String) { list.debugSelect(id) }

    func debugSelectMailbox(_ raw: String) {
        let mailbox: Mailbox = switch raw {
        case "undelivered": .undelivered
        case "allMail": .allMail
        case _ where raw.hasPrefix("agent:"): .agent(String(raw.dropFirst(6)))
        default: .inbox
        }
        sidebar.debugSelect(mailbox)
    }

    func debugDump() -> [String] { sidebar.debugRows() + list.debugRows() + thread.debugState() }
    #endif

    // MARK: NSWindowDelegate

    func windowDidBecomeKey(_ notification: Notification) {
        store.detailBecameVisible()
    }

    func windowDidChangeOcclusionState(_ notification: Notification) {
        if isThreadVisible { store.detailBecameVisible() }
    }

    func windowWillClose(_ notification: Notification) {
        thread.saveDraft()
        onClose()
    }

    // MARK: Commands

    @objc func showInbox(_ sender: Any?) { store.mailbox = .inbox }
    @objc func showUndelivered(_ sender: Any?) { store.mailbox = .undelivered }
    @objc func showAllMail(_ sender: Any?) { store.mailbox = .allMail }
    @objc func replyToThread(_ sender: Any?) { thread.focusReply() }
    @objc func sendReply(_ sender: Any?) { thread.sendReply() }
    @objc func closeThread(_ sender: Any?) { thread.closeThread() }

    @objc func copyThreadID(_ sender: Any?) {
        guard let id = store.selectedThreadID else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(id, forType: .string)
    }

    @objc func revealThreadInFinder(_ sender: Any?) {
        guard let id = store.selectedThreadID else { return }
        NSWorkspace.shared.activateFileViewerSelecting([store.threadDirectory(id)])
    }

    private func isEnabled(_ action: Selector?) -> Bool? {
        switch action {
        case #selector(replyToThread(_:)), #selector(copyThreadID(_:)), #selector(revealThreadInFinder(_:)):
            store.selectedThreadID != nil
        case #selector(sendReply(_:)):
            thread.canSendReply
        case #selector(closeThread(_:)):
            thread.canCloseThread
        default:
            nil
        }
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        let mailbox: Mailbox? = switch menuItem.action {
        case #selector(showInbox(_:)): .inbox
        case #selector(showUndelivered(_:)): .undelivered
        case #selector(showAllMail(_:)): .allMail
        default: nil
        }
        if let mailbox {
            menuItem.state = store.mailbox == mailbox ? .on : .off
            return true
        }
        return isEnabled(menuItem.action) ?? true
    }

    func validateToolbarItem(_ item: NSToolbarItem) -> Bool {
        isEnabled(item.action) ?? true
    }

    // MARK: NSToolbarDelegate

    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        [.toggleSidebar, .sidebarTrackingSeparator, .refresh, .flexibleSpace, .newMessage, .listTrackingSeparator,
         .flexibleSpace, .reply, .closeThread]
    }

    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        toolbarDefaultItemIdentifiers(toolbar) + [.space]
    }

    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier id: NSToolbarItem.Identifier,
                 willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        switch id {
        case .listTrackingSeparator:
            return NSTrackingSeparatorToolbarItem(identifier: id, splitView: split.splitView, dividerIndex: 1)
        case .newMessage:
            return button(id, "New Message", "square.and.pencil", #selector(AppDelegate.newMessage(_:)))
        case .refresh:
            return button(id, "Get New Mail", "arrow.clockwise", #selector(AppDelegate.refresh(_:)))
        case .reply:
            return button(id, "Reply", "arrowshape.turn.up.left", #selector(replyToThread(_:)))
        case .closeThread:
            return button(id, "Close Thread", "checkmark.circle", #selector(closeThread(_:)))
        default:
            return nil
        }
    }

    private func button(_ id: NSToolbarItem.Identifier, _ label: String, _ symbol: String, _ action: Selector) -> NSToolbarItem {
        let item = NSToolbarItem(itemIdentifier: id)
        item.label = label
        item.paletteLabel = label
        item.toolTip = label
        item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: label)
        item.action = action
        item.isBordered = true
        return item
    }
}
