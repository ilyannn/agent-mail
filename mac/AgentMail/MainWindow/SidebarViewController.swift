// The sidebar: a source list with Mailboxes (Inbox, Undelivered, All Mail)
// and Participants (the user first, then every repo on duty or in a thread
// with an on-duty dot; each counts the open threads waiting on it). Driven by
// a diffable data source keyed by row identity; counts are refreshed in
// place, never with reloadData(). A participant's context menu starts a new
// message to it.
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
            // The Inbox counts unread messages; the participants below count
            // threads (MailboxIndex.badge(for:)).
            case .inbox:
                cell.show(title: "Inbox", image: symbol("tray", "Inbox"), count: count,
                          tip: "Open threads waiting on you; the count is unread messages.",
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
                let image = if who.isMe {
                    NSImage(systemSymbolName: "person.fill", accessibilityDescription: "You")?
                        .withSymbolConfiguration(.init(paletteColors: [.tertiaryLabelColor]))
                } else {
                    NSImage.dot(who.onDuty ? .systemGreen : .tertiaryLabelColor, filled: who.onDuty,
                                label: who.onDuty ? "On duty" : "Not on duty")
                }
                cell.show(title: name, image: image, count: count, tip: who.tooltip,
                          accessibility: who.accessibilityLabel)
            }
        }
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
            return "sidebar\(selected) \(cell?.accessibilityLabel() ?? "?") | tip: \(tip)"
        }
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

    /// Participants have a menu; mailboxes and headers don't.
    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let row = tableView.clickedRow
        guard row >= 0, case let .mailbox(.agent(name))? = dataSource.itemIdentifier(forRow: row) else { return }
        for item in menuItems(forParticipant: name) { menu.addItem(item) }
    }

    /// New Message to the participant (plain New Message on the user's own
    /// row: not to myself), then Copy Name, for `agent-mail @NAME`.
    func menuItems(forParticipant name: String) -> [NSMenuItem] {
        func item(_ title: String, _ action: Selector) -> NSMenuItem {
            let i = NSMenuItem(title: title, action: action, keyEquivalent: "")
            i.target = self
            i.representedObject = name
            return i
        }
        let isMe = name == store.index.me
        return [item(isMe ? "New Message" : "New Message to \(name)", #selector(contextNewMessage(_:))),
                .separator(),
                item("Copy Name", #selector(contextCopyName(_:)))]
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

/// A sidebar row: icon, title and a count on the right.
final class SidebarCell: NSTableCellView {
    private let title = NSTextField.label()
    private let count = NSTextField.label(font: .monospacedDigitSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular),
                                          color: .secondaryLabelColor)
    private let icon = NSImageView()

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
        addSubview(icon)
        addSubview(count)
        count.alignment = .right
        count.setContentCompressionResistancePriority(.required, for: .horizontal)
        count.setContentHuggingPriority(.required, for: .horizontal)
        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 2),
            icon.centerYAnchor.constraint(equalTo: centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 18),
            title.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 6),
            title.centerYAnchor.constraint(equalTo: centerYAnchor),
            count.leadingAnchor.constraint(greaterThanOrEqualTo: title.trailingAnchor, constant: 6),
            count.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            count.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func show(title text: String, image: NSImage? = nil, count n: Int = 0, tip: String? = nil,
              accessibility: String? = nil) {
        title.stringValue = text
        icon.image = image
        count.stringValue = n > 0 ? "\(n)" : ""
        toolTip = tip
        setAccessibilityLabel(accessibility ?? (n > 0 ? "\(text), \(n)" : text))
    }
}
