// The thread list: one row per thread of the selected mailbox, keyed by
// thread id in a diffable data source, so selection and scroll position
// survive live refreshes. Rows show parties, subject, age, an unread dot and
// a state badge; visible rows are refreshed in place after each snapshot.
import AgentMailKit
import AppKit
import Observation

final class ThreadListViewController: NSViewController, NSTableViewDelegate, NSMenuDelegate {
    private let store: MailStore
    private let tableView = ThreadTableView()
    private let emptyLabel = NSTextField.label(font: .systemFont(ofSize: NSFont.systemFontSize + 2), color: .tertiaryLabelColor)
    private var dataSource: NSTableViewDiffableDataSource<Int, String>!
    private var applying = false
    private var observation: Task<Void, Never>?
    /// Return and Space in the list, handled by the window.
    var onReturn: () -> Void = {}
    var onSpace: () -> Void = {}

    init(store: MailStore) {
        self.store = store
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func loadView() {
        let column = NSTableColumn(identifier: .init("thread"))
        column.resizingMask = .autoresizingMask
        tableView.addTableColumn(column)
        tableView.headerView = nil
        tableView.style = .inset
        tableView.rowHeight = 50
        tableView.usesAutomaticRowHeights = false
        tableView.allowsMultipleSelection = false
        tableView.allowsEmptySelection = true
        tableView.delegate = self
        tableView.setAccessibilityLabel("Threads")
        tableView.onReturn = { [weak self] in self?.onReturn() }
        tableView.onSpace = { [weak self] in self?.onSpace() }
        let menu = NSMenu()
        menu.delegate = self
        tableView.menu = menu

        let scroll = NSScrollView()
        scroll.documentView = tableView
        scroll.hasVerticalScroller = true
        let container = NSView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(scroll)
        emptyLabel.alignment = .center
        container.addSubview(emptyLabel)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: container.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            emptyLabel.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            emptyLabel.leadingAnchor.constraint(greaterThanOrEqualTo: container.leadingAnchor, constant: 16),
            emptyLabel.trailingAnchor.constraint(lessThanOrEqualTo: container.trailingAnchor, constant: -16),
        ])
        emptyLabel.maximumNumberOfLines = 4
        emptyLabel.lineBreakMode = .byWordWrapping
        view = container
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        dataSource = NSTableViewDiffableDataSource(tableView: tableView) { [unowned self] tableView, _, _, id in
            let cellID = NSUserInterfaceItemIdentifier("thread")
            let cell = (tableView.makeView(withIdentifier: cellID, owner: nil) as? ThreadCellView) ?? ThreadCellView()
            cell.identifier = cellID
            if let t = store.index.thread(id) { cell.configure(t, me: store.index.me, now: .now) }
            return cell
        }
        apply()
        // `status` changes on every refresh (its clock moves), so ages stay current.
        let changes = Observations { [store] in (store.status, store.index, store.mailbox, store.selectedThreadID, store.refreshError, store.hasLoaded) }
        observation = Task { [weak self] in
            for await _ in changes { self?.apply() }
        }
    }

    var focusTarget: NSView { tableView }

    private func apply() {
        let index = store.index
        let ids = index.threads(in: store.mailbox).map(\.id)
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(ids)
        applying = true
        defer { applying = false }
        let animate = (view.window?.isVisible ?? false) && dataSource.snapshot().numberOfItems > 0
        dataSource.apply(snapshot, animatingDifferences: animate)
        let now = Date.now
        tableView.enumerateAvailableRowViews { rowView, row in
            guard let cell = rowView.view(atColumn: 0) as? ThreadCellView,
                  let id = dataSource.itemIdentifier(forRow: row), let t = index.thread(id) else { return }
            cell.configure(t, me: index.me, now: now)
        }
        if let id = store.selectedThreadID, let row = dataSource.row(forItemIdentifier: id), row >= 0 {
            if tableView.selectedRow != row {
                tableView.selectRowIndexes([row], byExtendingSelection: false)
                tableView.scrollRowToVisible(row)
            }
        } else if tableView.selectedRow >= 0 {
            tableView.deselectAll(nil)
        }
        updateEmptyState(isEmpty: ids.isEmpty)
        if store.hasLoaded {
            Timing.note("list applied: \(store.mailbox.title), \(ids.count) rows")
        }
    }

    private func updateEmptyState(isEmpty: Bool) {
        if let error = store.refreshError, !store.hasLoaded {
            emptyLabel.stringValue = error.errorDescription ?? "Can’t read mail"
        } else if !store.hasLoaded {
            emptyLabel.stringValue = "Loading…"
        } else {
            emptyLabel.stringValue = switch store.mailbox {
            case .inbox: "Nothing waiting on you"
            case .undelivered: "Everything is being listened for"
            case .allMail: "No threads yet"
            case let .agent(name): "No threads with \(name)"
            }
        }
        emptyLabel.isHidden = !isEmpty
    }

    /// Selects a thread's row (after the mailbox was switched to show it).
    func reveal(_ id: String) {
        apply()
    }

    #if DEBUG
    func debugSelect(_ id: String) {
        guard let row = dataSource.row(forItemIdentifier: id), row >= 0 else { return }
        view.window?.makeFirstResponder(tableView)
        tableView.selectRowIndexes([row], byExtendingSelection: false)
    }

    func debugRows() -> [String] {
        (0..<tableView.numberOfRows).map { row in
            let cell = tableView.view(atColumn: 0, row: row, makeIfNecessary: false)
            let selected = tableView.selectedRow == row ? "*" : " "
            return "row\(selected) \(dataSource.itemIdentifier(forRow: row) ?? "?") | \(cell?.accessibilityLabel() ?? "?")"
        } + ["emptyLabel: \(emptyLabel.isHidden ? "hidden" : emptyLabel.stringValue)"]
    }
    #endif

    // MARK: NSTableViewDelegate

    func tableViewSelectionDidChange(_ notification: Notification) {
        guard !applying else { return }
        let row = tableView.selectedRow
        store.select(threadID: row >= 0 ? dataSource.itemIdentifier(forRow: row) : nil)
    }

    // MARK: Context menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let row = tableView.clickedRow
        guard row >= 0, let id = dataSource.itemIdentifier(forRow: row), let t = store.index.thread(id) else { return }
        func add(_ title: String, _ action: Selector, enabled: Bool = true) {
            let i = NSMenuItem(title: title, action: enabled ? action : nil, keyEquivalent: "")
            i.target = self
            i.representedObject = id
            menu.addItem(i)
        }
        add("Reply", #selector(contextReply(_:)))
        add("Close Thread", #selector(contextClose(_:)), enabled: t.isOpen)
        menu.addItem(.separator())
        add("Copy Thread ID", #selector(contextCopyID(_:)))
        add("Copy Thread Number", #selector(contextCopyNumber(_:)), enabled: t.number != nil)
        add("Show in Finder", #selector(contextReveal(_:)))
    }

    var onContextReply: (String) -> Void = { _ in }
    var onContextClose: (String) -> Void = { _ in }

    @objc private func contextReply(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        onContextReply(id)
    }

    @objc private func contextClose(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        onContextClose(id)
    }

    @objc private func contextCopyID(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(id, forType: .string)
    }

    @objc private func contextCopyNumber(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String, let n = store.index.thread(id)?.number else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString("\(n)", forType: .string)
    }

    @objc private func contextReveal(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        NSWorkspace.shared.activateFileViewerSelecting([store.threadDirectory(id)])
    }
}

/// Return focuses the reply box; Space scrolls the thread.
final class ThreadTableView: NSTableView {
    var onReturn: () -> Void = {}
    var onSpace: () -> Void = {}

    override func keyDown(with event: NSEvent) {
        let plain = event.modifierFlags.intersection(.deviceIndependentFlagsMask).subtracting([.numericPad, .function]).isEmpty
        switch event.charactersIgnoringModifiers {
        case "\r" where plain: onReturn()
        case " " where plain: onSpace()
        default: super.keyDown(with: event)
        }
    }
}

/// One thread: `from → to` and age on top, subject and badge below.
final class ThreadCellView: NSTableCellView {
    private let dot = NSImageView()
    private let parties = NSTextField.label()
    private let age = NSTextField.label(font: .monospacedDigitSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular),
                                        color: .secondaryLabelColor)
    private let subject = NSTextField.label(color: .secondaryLabelColor)
    private let badge = BadgeView()

    init() {
        super.init(frame: .zero)
        for v in [dot, parties, age, subject, badge] as [NSView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            addSubview(v)
        }
        textField = subject
        age.alignment = .right
        age.setContentCompressionResistancePriority(.required, for: .horizontal)
        age.setContentHuggingPriority(.required, for: .horizontal)
        parties.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        subject.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        NSLayoutConstraint.activate([
            dot.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 2),
            dot.centerYAnchor.constraint(equalTo: parties.centerYAnchor),
            dot.widthAnchor.constraint(equalToConstant: 10),
            parties.leadingAnchor.constraint(equalTo: dot.trailingAnchor, constant: 4),
            parties.topAnchor.constraint(equalTo: topAnchor, constant: 7),
            age.leadingAnchor.constraint(greaterThanOrEqualTo: parties.trailingAnchor, constant: 6),
            age.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4),
            age.firstBaselineAnchor.constraint(equalTo: parties.firstBaselineAnchor),
            subject.leadingAnchor.constraint(equalTo: parties.leadingAnchor),
            subject.topAnchor.constraint(equalTo: parties.bottomAnchor, constant: 3),
            badge.leadingAnchor.constraint(greaterThanOrEqualTo: subject.trailingAnchor, constant: 6),
            badge.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4),
            badge.centerYAnchor.constraint(equalTo: subject.centerYAnchor),
        ])
        setAccessibilityElement(true)
        setAccessibilityRole(.cell)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func configure(_ t: ThreadSummary, me: String, now: Date) {
        let unread = t.unread > 0
        dot.image = unread ? NSImage.dot(.controlAccentColor, label: "Unread") : nil
        let from = t.lastFrom ?? t.from ?? "?"
        let to = t.owner ?? "?"
        parties.stringValue = "\(from) → \(to)"
        parties.font = .systemFont(ofSize: NSFont.systemFontSize, weight: unread ? .semibold : .regular)
        subject.stringValue = t.subject
        subject.textColor = unread ? .labelColor : .secondaryLabelColor
        let seconds = Int(now.timeIntervalSince(t.updated ?? t.created ?? now))
        age.stringValue = Formatting.age(seconds: seconds)

        let state: (String, NSColor)? =
            if t.isUndelivered { ("Undelivered", .systemOrange) }
            else if t.isWaiting(on: me) { ("Your turn", .controlAccentColor) }
            else if !t.isOpen { ("Closed", .secondaryLabelColor) }
            else if t.listener == "on-duty" { ("On duty", .systemGreen) }
            else { nil }
        badge.text = state?.0 ?? ""
        badge.color = state?.1 ?? .secondaryLabelColor

        var spoken = [String]()
        if unread { spoken.append("\(t.unread) unread") }
        spoken.append("from \(from) to \(to)")
        spoken.append(t.subject)
        spoken.append("\(Formatting.spokenAge(seconds: seconds)) ago")
        if let s = state?.0 { spoken.append(s) }
        setAccessibilityLabel(spoken.joined(separator: ", "))
        toolTip = "#\(t.number.map(String.init) ?? "?") \(t.id)\n\(t.count) message\(t.count == 1 ? "" : "s"), "
            + (t.isOpen ? "waiting on \(to)" : "closed")
    }
}
