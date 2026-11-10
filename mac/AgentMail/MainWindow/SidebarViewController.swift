// The sidebar: a source list with Mailboxes (Inbox, Undelivered, All Mail)
// and Participants (the user first, then every agent in the directory and
// every other name in a thread; each counts the open threads waiting on it).
// A participant's dot is filled while one of its sessions is in a turn
// (green: working; orange: waiting on the user), a ring otherwise (green
// when on duty or on call, grey with the name dimmed when nothing picks its
// mail up); a warning symbol after its name says something needs the user
// (a folder serve can't use), its tooltip what; the row's tooltip lists
// what its sessions are doing. Driven by a diffable data source keyed by
// row identity; counts are refreshed in place, never with reloadData(). A
// participant's context menu starts a new message to it and puts a
// directory agent on or off call; every row's offers Add Folder….
import AgentMailKit
import AppKit
import Observation

enum SidebarRow: Hashable {
    case header(String)
    case mailbox(Mailbox)
}

final class SidebarViewController: NSViewController, NSTableViewDelegate, NSMenuDelegate {
    private let store: MailStore
    private let tableView = NSTableView()
    private var dataSource: NSTableViewDiffableDataSource<Int, SidebarRow>!
    private var applying = false
    private var observation: Task<Void, Never>?
    /// New Message from a participant's menu: to that repo, or to nobody
    /// yet from the user's own row.
    var onNewMessage: (String?) -> Void = { _ in }
    /// Add Folder… from the menu: the window asks for the folder.
    var onAddFolder: () -> Void = {}

    init(store: MailStore) {
        self.store = store
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func loadView() {
        let column = NSTableColumn(identifier: .init("sidebar"))
        column.resizingMask = .autoresizingMask
        tableView.addTableColumn(column)
        tableView.headerView = nil
        tableView.style = .sourceList
        tableView.floatsGroupRows = false
        tableView.allowsEmptySelection = false
        tableView.delegate = self
        tableView.setAccessibilityLabel("Mailboxes and participants")
        let menu = NSMenu()
        menu.delegate = self
        tableView.menu = menu
        let scroll = NSScrollView()
        scroll.documentView = tableView
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        view = scroll
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        dataSource = NSTableViewDiffableDataSource(tableView: tableView) { [unowned self] tableView, _, _, row in
            cell(for: row, in: tableView)
        }
        apply()
        let changes = Observations { [store] in (store.index, store.mailbox) }
        observation = Task { [weak self] in
            for await _ in changes { self?.apply() }
        }
    }

    private func apply() {
        var snapshot = NSDiffableDataSourceSnapshot<Int, SidebarRow>()
        snapshot.appendSections([0])
        snapshot.appendItems([.header("Mailboxes"), .mailbox(.inbox), .mailbox(.undelivered), .mailbox(.allMail)])
        // Never empty: the user's own row is always first.
        let participants = store.index.participants
        snapshot.appendItems([.header("Participants")] + participants.map { .mailbox(.agent($0.name)) })
        applying = true
        defer { applying = false }
        dataSource.apply(snapshot, animatingDifferences: false)
        tableView.enumerateAvailableRowViews { rowView, row in
            guard let cell = rowView.view(atColumn: 0) as? SidebarCell,
                  let item = dataSource.itemIdentifier(forRow: row) else { return }
            configure(cell, for: item)
        }
        let selected = SidebarRow.mailbox(store.mailbox)
        if let row = dataSource.row(forItemIdentifier: selected), row >= 0, tableView.selectedRow != row {
            tableView.selectRowIndexes([row], byExtendingSelection: false)
        }
    }

    private func cell(for row: SidebarRow, in tableView: NSTableView) -> NSView {
        let id = NSUserInterfaceItemIdentifier(row.isHeader ? "header" : "item")
        let cell = (tableView.makeView(withIdentifier: id, owner: nil) as? SidebarCell) ?? SidebarCell(header: row.isHeader)
        cell.identifier = id
        configure(cell, for: row)
        return cell
    }

    private func configure(_ cell: SidebarCell, for row: SidebarRow) {
        let index = store.index
        switch row {
        case let .header(title):
            cell.show(title: title)
        case let .mailbox(mailbox):
            let count = index.badge(for: mailbox)
            switch mailbox {
            // The Inbox counts unread messages addressed to the user, not
            // what agents sent each other in those threads; the participants
            // below count threads (MailboxIndex.badge(for:)).
            case .inbox:
                cell.show(title: "Inbox", image: symbol("tray", "Inbox"), count: count,
                          tip: "Open threads waiting on you; the count is unread messages to you.",
                          accessibility: count > 0 ? "Inbox, \(count) unread message\(count == 1 ? "" : "s")" : "Inbox")
            case .undelivered:
                cell.show(title: "Undelivered", image: symbol("paperplane", "Undelivered"), count: count,
                          tip: "Open threads whose recipient has nobody on duty.",
                          accessibility: count > 0 ? "Undelivered, \(count) thread\(count == 1 ? "" : "s")" : "Undelivered")
            case .allMail:
                cell.show(title: "All Mail", image: symbol("tray.2", "All Mail"), count: 0,
                          tip: "Every thread, including mail between agents, in the order chosen under View › Sort By.")
            case let .agent(name):
                // A row from the previous snapshot may outlive its participant
                // until the next apply().
                let who = index.participant(name) ?? AgentInfo(name: name, isMe: name == index.me)
                let notices = who.notices
                cell.show(title: name, image: Self.icon(for: who), count: count, tip: who.tooltip,
                          accessibility: who.accessibilityLabel, dimmed: Self.isDimmed(who),
                          warning: notices.isEmpty ? nil : notices.map(\.text).joined(separator: "\n"))
            }
        }
    }

    /// A participant's dot: the user's own figure; filled while one of its
    /// sessions is in a turn, orange when one waits on the user (a prompt),
    /// else green: it is working. A ring otherwise: green on duty, or on call
    /// while serve runs (it starts a session when mail comes); grey on call
    /// while serve doesn't run; fainter grey when nothing picks its mail up,
    /// whose name is dimmed too (isDimmed(_:)).
    static func icon(for who: AgentInfo) -> NSImage? {
        if who.isMe {
            return NSImage(systemSymbolName: "person.fill", accessibilityDescription: "You")?
                .withSymbolConfiguration(.init(paletteColors: [.tertiaryLabelColor]))
        }
        if who.needsUser { return NSImage.dot(.systemOrange, label: "Waiting on you") }
        if who.isWorking { return NSImage.dot(.systemGreen, label: "Working") }
        if who.onDuty { return NSImage.dot(.systemGreen, filled: false, label: "On duty") }
        if who.onCall {
            return who.serveRunning ? NSImage.dot(.systemGreen, filled: false, label: "On call")
                : NSImage.dot(.secondaryLabelColor, filled: false, label: "On call, serve isn’t running")
        }
        return NSImage.dot(.tertiaryLabelColor, filled: false, label: who.inDirectory ? "Off call" : "Not on duty")
    }

    /// Whether a participant's name is dimmed: nothing picks its mail up
    /// (off call, or not in the directory) and none of its sessions is in a
    /// turn; never the user's own.
    static func isDimmed(_ who: AgentInfo) -> Bool {
        !who.isMe && !who.onDuty && !who.onCall && !who.isWorking && !who.needsUser
    }

    private func symbol(_ name: String, _ label: String) -> NSImage? {
        NSImage(systemSymbolName: name, accessibilityDescription: label)
    }

    #if DEBUG
    func debugSelect(_ mailbox: Mailbox) {
        guard let row = dataSource.row(forItemIdentifier: .mailbox(mailbox)), row >= 0 else { return }
        tableView.selectRowIndexes([row], byExtendingSelection: false)
    }

    func debugRows() -> [String] {
        (0..<tableView.numberOfRows).map { row in
            let cell = tableView.view(atColumn: 0, row: row, makeIfNecessary: false)
            let selected = tableView.selectedRow == row ? "*" : " "
            let tip = cell?.toolTip?.replacingOccurrences(of: "\n", with: " / ") ?? ""
            let extra = (cell as? SidebarCell)?.debugIcons ?? ""
            return "sidebar\(selected) \(cell?.accessibilityLabel() ?? "?") | tip: \(tip)\(extra)"
        }
    }

    /// The context menu a right click on `mailbox`'s row gets (nil: the
    /// empty space below the rows), as titles: `✓` on, `(off)` disabled.
    func debugMenu(for mailbox: Mailbox?) -> [String] {
        menuItems(for: mailbox).map { i in
            i.isSeparatorItem ? "---" : i.title + (i.state == .on ? "✓" : "") + (i.action == nil ? "(off)" : "")
        }
    }

    /// Picks the item titled `title` from that menu, as a click would.
    func debugPickMenuItem(_ title: String, for mailbox: Mailbox?) -> Bool {
        guard let item = menuItems(for: mailbox).first(where: { $0.title == title }), let action = item.action
        else { return false }
        return NSApp.sendAction(action, to: item.target, from: item)
    }
    #endif

    // MARK: NSTableViewDelegate

    func tableView(_ tableView: NSTableView, isGroupRow row: Int) -> Bool {
        dataSource.itemIdentifier(forRow: row)?.isHeader ?? false
    }

    func tableView(_ tableView: NSTableView, shouldSelectRow row: Int) -> Bool {
        !(dataSource.itemIdentifier(forRow: row)?.isHeader ?? true)
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        guard !applying, case let .mailbox(mailbox)? = dataSource.itemIdentifier(forRow: tableView.selectedRow)
        else { return }
        store.mailbox = mailbox
    }

    // MARK: Context menu

    /// Participants have their own items; every row, and the space below
    /// them, offers Add Folder….
    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let row = tableView.clickedRow
        var mailbox: Mailbox?
        if row >= 0, case let .mailbox(m)? = dataSource.itemIdentifier(forRow: row) { mailbox = m }
        for item in menuItems(for: mailbox) { menu.addItem(item) }
    }

    /// A row's menu: a participant's items, then Add Folder….
    func menuItems(for mailbox: Mailbox?) -> [NSMenuItem] {
        var items = [NSMenuItem]()
        if case let .agent(name)? = mailbox { items = menuItems(forParticipant: name) + [.separator()] }
        let add = NSMenuItem(title: "Add Folder…", action: #selector(contextAddFolder(_:)), keyEquivalent: "")
        add.target = self
        add.toolTip = "Add a folder to the agent directory, on call, so mail can go to its agent"
        return items + [add]
    }

    /// New Message to the participant (plain New Message on the user's own
    /// row: not to myself); On Call, checked while it is, for an agent in the
    /// directory; then Copy Name, for `agent-mail @NAME`.
    func menuItems(forParticipant name: String) -> [NSMenuItem] {
        func item(_ title: String, _ action: Selector) -> NSMenuItem {
            let i = NSMenuItem(title: title, action: action, keyEquivalent: "")
            i.target = self
            i.representedObject = name
            return i
        }
        let isMe = name == store.index.me
        var items = [item(isMe ? "New Message" : "New Message to \(name)", #selector(contextNewMessage(_:)))]
        if let who = store.index.participant(name), who.inDirectory, !isMe {
            let onCall = item("On Call", #selector(contextToggleOnCall(_:)))
            onCall.state = who.onCall ? .on : .off
            onCall.toolTip = who.onCall
                ? "Take \(name) off call: serve stops delivering its mail (agent-mail oncall remove \(name))"
                : "Put \(name) on call: serve starts a session for each thread (agent-mail oncall add \(name))"
            items.append(onCall)
        }
        return items + [.separator(), item("Copy Name", #selector(contextCopyName(_:)))]
    }

    @objc private func contextAddFolder(_ sender: NSMenuItem) { onAddFolder() }

    /// Flips the agent's on-call flag through the CLI; the store watcher
    /// brings the change back. A refusal is shown on the window.
    @objc private func contextToggleOnCall(_ sender: NSMenuItem) {
        guard let name = sender.representedObject as? String, let who = store.index.participant(name) else { return }
        Task {
            do {
                try await store.setOnCall(name, !who.onCall)
            } catch {
                if let window = view.window {
                    window.presentError(error, modalFor: window, delegate: nil, didPresent: nil, contextInfo: nil)
                } else {
                    NSApp.presentError(error)
                }
            }
        }
    }

    @objc private func contextNewMessage(_ sender: NSMenuItem) {
        guard let name = sender.representedObject as? String else { return }
        onNewMessage(name == store.index.me ? nil : name)
    }

    @objc private func contextCopyName(_ sender: NSMenuItem) {
        guard let name = sender.representedObject as? String else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(name, forType: .string)
    }
}

private extension SidebarRow {
    var isHeader: Bool {
        if case .header = self { return true }
        return false
    }
}

/// A sidebar row: icon, title, a warning symbol when something needs the
/// user (with its own tooltip), and a count on the right.
final class SidebarCell: NSTableCellView {
    private let title = NSTextField.label()
    private let count = NSTextField.label(font: .monospacedDigitSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular),
                                          color: .secondaryLabelColor)
    private let icon = NSImageView()
    private let warning = NSImageView()

    init(header: Bool) {
        super.init(frame: .zero)
        textField = title
        addSubview(title)
        if header {
            title.font = .systemFont(ofSize: NSFont.smallSystemFontSize, weight: .semibold)
            title.textColor = .secondaryLabelColor
            NSLayoutConstraint.activate([
                title.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 2),
                title.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -4),
                title.centerYAnchor.constraint(equalTo: centerYAnchor),
            ])
            return
        }
        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.imageScaling = .scaleProportionallyDown
        imageView = icon
        warning.translatesAutoresizingMaskIntoConstraints = false
        warning.imageScaling = .scaleNone
        warning.image = NSImage(systemSymbolName: "exclamationmark.triangle.fill", accessibilityDescription: "Warning")?
            .withSymbolConfiguration(.init(pointSize: 11, weight: .regular).applying(.preferringMulticolor()))
        warning.isHidden = true
        addSubview(icon)
        addSubview(warning)
        addSubview(count)
        count.alignment = .right
        count.setContentCompressionResistancePriority(.required, for: .horizontal)
        count.setContentHuggingPriority(.required, for: .horizontal)
        title.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        warning.setContentCompressionResistancePriority(.required, for: .horizontal)
        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 2),
            icon.centerYAnchor.constraint(equalTo: centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 18),
            title.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 6),
            title.centerYAnchor.constraint(equalTo: centerYAnchor),
            warning.leadingAnchor.constraint(equalTo: title.trailingAnchor, constant: 4),
            warning.centerYAnchor.constraint(equalTo: centerYAnchor),
            count.leadingAnchor.constraint(greaterThanOrEqualTo: warning.trailingAnchor, constant: 6),
            count.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            count.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    /// `warning`: what needs the user, the warning symbol's tooltip; nil
    /// hides the symbol.
    func show(title text: String, image: NSImage? = nil, count n: Int = 0, tip: String? = nil,
              accessibility: String? = nil, dimmed: Bool = false, warning warningText: String? = nil) {
        title.stringValue = text
        // Headers keep their own colour, set once in init.
        if imageView != nil { title.textColor = dimmed ? .secondaryLabelColor : .labelColor }
        icon.image = image
        count.stringValue = n > 0 ? "\(n)" : ""
        toolTip = tip
        warning.isHidden = warningText == nil
        warning.toolTip = warningText
        setAccessibilityLabel(accessibility ?? (n > 0 ? "\(text), \(n)" : text))
    }

    #if DEBUG
    /// The icon's and the warning's descriptions, for the debug dump.
    var debugIcons: String {
        guard imageView != nil else { return "" }
        var out = (title.textColor == .secondaryLabelColor ? " | dimmed" : "")
            + " | icon: \(icon.image?.accessibilityDescription ?? "-")"
        if !warning.isHidden { out += " | warn: \(warning.toolTip?.replacingOccurrences(of: "\n", with: " / ") ?? "")" }
        return out
    }
    #endif
}
