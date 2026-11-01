// The sidebar: a source list with Mailboxes (Inbox, Undelivered, All Mail)
// and Agents (every repo on duty or in a thread, with an on-duty dot and its
// unread count). Driven by a diffable data source keyed by row identity;
// counts are refreshed in place, never with reloadData().
import AgentMailKit
import AppKit
import Observation

enum SidebarRow: Hashable {
    case header(String)
    case mailbox(Mailbox)
}

final class SidebarViewController: NSViewController, NSTableViewDelegate {
    private let store: MailStore
    private let tableView = NSTableView()
    private var dataSource: NSTableViewDiffableDataSource<Int, SidebarRow>!
    private var applying = false
    private var observation: Task<Void, Never>?

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
        tableView.setAccessibilityLabel("Mailboxes and agents")
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
        let agents = store.index.agents
        if !agents.isEmpty {
            snapshot.appendItems([.header("Agents")] + agents.map { .mailbox(.agent($0.name)) })
        }
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
            case .inbox:
                cell.show(title: "Inbox", image: symbol("tray", "Inbox"), count: count,
                          tip: "Open threads waiting on you; the count is unread messages.")
            case .undelivered:
                cell.show(title: "Undelivered", image: symbol("paperplane", "Undelivered"), count: count,
                          tip: "Open threads whose recipient has nobody on duty.")
            case .allMail:
                cell.show(title: "All Mail", image: symbol("tray.2", "All Mail"), count: 0,
                          tip: "Every thread, including mail between agents; closed threads last.")
            case let .agent(name):
                let agent = index.agents.first { $0.name == name }
                let onDuty = agent?.onDuty ?? false
                let image = NSImage.dot(onDuty ? .systemGreen : .tertiaryLabelColor, filled: onDuty,
                                        label: onDuty ? "On duty" : "Not on duty")
                var tip = "\(name): " + (onDuty ? "on duty" : "nobody on duty")
                if let agent {
                    let sessions = agent.sessions.filter(\.onDuty).map { "\($0.session) (\($0.state))" }
                    if !sessions.isEmpty { tip += " — " + sessions.joined(separator: ", ") }
                    tip += "\n\(agent.unread) unread by \(name), \(agent.waiting) thread\(agent.waiting == 1 ? "" : "s") waiting on it"
                }
                cell.show(title: name, image: image, count: count, tip: tip,
                          accessibility: "\(name), \(onDuty ? "on duty" : "not on duty"), \(count) unread")
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
