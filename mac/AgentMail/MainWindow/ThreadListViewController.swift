// The thread list: one row per thread of the selected mailbox, in the chosen
// order, keyed by thread id in a diffable data source, so selection and
// scroll position survive live refreshes and re-sorting. Rows show the
// thread's flow, subject, age, an unread dot and a state icon; visible rows
// are refreshed in place after each snapshot. The new-thread draft, while
// there is one, is a row pinned at the top of the mailboxes its thread will
// be in once sent: All Mail, mine, and the recipient's. Above the list, a
// participant's mailbox has a scope bar: All, Sent, Received, Waiting (the
// participant filter), and under it what needs the user about the
// participant, when anything does (a folder serve can't use, a checkout it
// can't land in, unlanded sessions).
import AgentMailKit
import AppKit
import Observation

final class ThreadListViewController: NSViewController, NSTableViewDelegate, NSMenuDelegate {
    /// The draft row's item: thread ids are path components, never "/draft".
    static let draftRow = "/draft"

    private let store: MailStore
    private let tableView = ThreadTableView()
    private let emptyLabel = NSTextField.label(font: .systemFont(ofSize: NSFont.systemFontSize + 2), color: .tertiaryLabelColor)
    private let scopeBar = NSView()
    private let scopeControl = NSSegmentedControl(labels: ParticipantFilter.allCases.map(\.title),
                                                  trackingMode: .selectOne, target: nil, action: nil)
    /// Below the scope bar: what needs the user about the participant (its
    /// AgentNotices), when anything does.
    private let noticeBar = NoticeBar()
    /// The list starts under the toolbar without the scope bar, below the
    /// bar with it, and below the notices when they show.
    private var scrollTopToView: NSLayoutConstraint!
    private var scrollTopToScope: NSLayoutConstraint!
    private var scrollTopToNotices: NSLayoutConstraint!
    private var dataSource: NSTableViewDiffableDataSource<Int, String>!
    private var applying = false
    /// What the last snapshot showed, to tell a re-sort from a mailbox switch.
    private var appliedMailbox: Mailbox?
    private var appliedOrder: ThreadOrder?
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
        buildScopeBar(in: container)
        noticeBar.translatesAutoresizingMaskIntoConstraints = false
        noticeBar.isHidden = true
        container.addSubview(noticeBar)
        emptyLabel.alignment = .center
        container.addSubview(emptyLabel)
        scrollTopToView = scroll.topAnchor.constraint(equalTo: container.topAnchor)
        scrollTopToScope = scroll.topAnchor.constraint(equalTo: scopeBar.bottomAnchor)
        scrollTopToNotices = scroll.topAnchor.constraint(equalTo: noticeBar.bottomAnchor)
        NSLayoutConstraint.activate([
            noticeBar.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            noticeBar.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            noticeBar.topAnchor.constraint(equalTo: scopeBar.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            scrollTopToView,
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

    /// The participant filter as a small segmented control, centred below
    /// the toolbar, with a separator under it.
    private func buildScopeBar(in container: NSView) {
        scopeControl.controlSize = .small
        scopeControl.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        scopeControl.segmentDistribution = .fit
        scopeControl.target = self
        scopeControl.action = #selector(scopeChanged(_:))
        scopeControl.setAccessibilityLabel("Participant filter")
        scopeControl.translatesAutoresizingMaskIntoConstraints = false
        let separator = NSBox()
        separator.boxType = .separator
        separator.translatesAutoresizingMaskIntoConstraints = false
        scopeBar.translatesAutoresizingMaskIntoConstraints = false
        scopeBar.addSubview(scopeControl)
        scopeBar.addSubview(separator)
        scopeBar.isHidden = true
        container.addSubview(scopeBar)
        NSLayoutConstraint.activate([
            scopeBar.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            scopeBar.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            scopeBar.topAnchor.constraint(equalTo: container.safeAreaLayoutGuide.topAnchor),
            scopeControl.centerXAnchor.constraint(equalTo: scopeBar.centerXAnchor),
            scopeControl.leadingAnchor.constraint(greaterThanOrEqualTo: scopeBar.leadingAnchor, constant: 8),
            scopeControl.topAnchor.constraint(equalTo: scopeBar.topAnchor, constant: 6),
            separator.topAnchor.constraint(equalTo: scopeControl.bottomAnchor, constant: 6),
            separator.leadingAnchor.constraint(equalTo: scopeBar.leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: scopeBar.trailingAnchor),
            separator.bottomAnchor.constraint(equalTo: scopeBar.bottomAnchor),
        ])
    }

    /// Shows the scope bar for a participant's mailbox only, with the
    /// current filter selected and each segment's tooltip naming the
    /// participant and counting its threads; below it, the participant's
    /// notices when it has any.
    private func updateScopeBar() {
        guard case let .agent(name) = store.mailbox else {
            scopeBar.isHidden = true
            noticeBar.isHidden = true
            pinListTop(scrollTopToView)
            return
        }
        scopeBar.isHidden = false
        let notices = store.index.participant(name)?.notices ?? []
        noticeBar.show(notices)
        noticeBar.isHidden = notices.isEmpty
        pinListTop(notices.isEmpty ? scrollTopToScope : scrollTopToNotices)
        let isMe = name == store.index.me
        for (i, filter) in ParticipantFilter.allCases.enumerated() {
            let n = store.index.count(filter, for: name)
            scopeControl.setToolTip("\(filter.explanation(for: name, isMe: isMe)) (\(n))", forSegment: i)
        }
        scopeControl.selectedSegment = store.participantFilter.menuTag
    }

    /// The list's top follows `constraint` alone.
    private func pinListTop(_ constraint: NSLayoutConstraint) {
        guard !constraint.isActive else { return }
        for c in [scrollTopToView, scrollTopToScope, scrollTopToNotices] where c !== constraint { c?.isActive = false }
        constraint.isActive = true
    }

    @objc private func scopeChanged(_ sender: NSSegmentedControl) {
        guard ParticipantFilter.allCases.indices.contains(sender.selectedSegment) else { return }
        store.participantFilter = ParticipantFilter.allCases[sender.selectedSegment]
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        dataSource = NSTableViewDiffableDataSource(tableView: tableView) { [unowned self] tableView, _, _, id in
            if id == Self.draftRow {
                let cellID = NSUserInterfaceItemIdentifier("draft")
                let cell = (tableView.makeView(withIdentifier: cellID, owner: nil) as? DraftCellView) ?? DraftCellView()
                cell.identifier = cellID
                if let draft = store.newDraft { cell.configure(draft) }
                return cell
            }
            let cellID = NSUserInterfaceItemIdentifier("thread")
            let cell = (tableView.makeView(withIdentifier: cellID, owner: nil) as? ThreadCellView) ?? ThreadCellView()
            cell.identifier = cellID
            if let t = store.index.thread(id) { cell.configure(t, me: store.index.me, now: .now) }
            return cell
        }
        apply()
        // `status` changes on every refresh (its clock moves), so ages stay current.
        let changes = Observations { [store] in
            (store.status, store.index, store.mailbox, store.threadOrder, store.participantFilter,
             store.selectedThreadID, store.refreshError, store.hasLoaded, store.newDraft, store.isShowingDraft)
        }
        observation = Task { [weak self] in
            for await _ in changes { self?.apply() }
        }
    }

    var focusTarget: NSView { tableView }

    private func apply() {
        let index = store.index
        let mailbox = store.mailbox, order = store.threadOrder
        let ids = store.shownThreads.map(\.id)
        updateScopeBar()
        // A new order in the same mailbox: the selected row stays where it is
        // on screen and the others move around it, without animation.
        let resorted = appliedMailbox == mailbox && appliedOrder != nil && appliedOrder != order
        appliedMailbox = mailbox
        appliedOrder = order
        let anchor = resorted ? selectedRowOffset() : nil
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems((store.showsDraftRow ? [Self.draftRow] : []) + ids)
        applying = true
        defer { applying = false }
        let animate = !resorted && (view.window?.isVisible ?? false) && dataSource.snapshot().numberOfItems > 0
        dataSource.apply(snapshot, animatingDifferences: animate)
        let now = Date.now
        tableView.enumerateAvailableRowViews { rowView, row in
            guard let id = dataSource.itemIdentifier(forRow: row) else { return }
            if let cell = rowView.view(atColumn: 0) as? DraftCellView, id == Self.draftRow, let draft = store.newDraft {
                cell.configure(draft)
            } else if let cell = rowView.view(atColumn: 0) as? ThreadCellView, let t = index.thread(id) {
                cell.configure(t, me: index.me, now: now)
            }
        }
        let selectedItem = store.isShowingDraft ? Self.draftRow : store.selectedThreadID
        if let id = selectedItem, let row = dataSource.row(forItemIdentifier: id), row >= 0 {
            if tableView.selectedRow != row {
                tableView.selectRowIndexes([row], byExtendingSelection: false)
                if anchor == nil { tableView.scrollRowToVisible(row) }
            }
            if let anchor { scroll(row: row, toOffset: anchor) }
        } else if tableView.selectedRow >= 0 {
            tableView.deselectAll(nil)
        }
        // The draft row doesn't make a mailbox any less empty.
        updateEmptyState(isEmpty: ids.isEmpty)
        if store.hasLoaded {
            Timing.note("list applied: \(store.mailbox.title), \(ids.count) rows")
        }
    }

    /// How far below the top of the visible area the selected row starts;
    /// nil when nothing is selected or the row is scrolled out of view.
    private func selectedRowOffset() -> CGFloat? {
        guard let clip = tableView.enclosingScrollView?.contentView, let id = store.selectedThreadID,
              let row = dataSource.row(forItemIdentifier: id), row >= 0 else { return nil }
        let rect = tableView.rect(ofRow: row)
        guard rect.intersects(clip.documentVisibleRect) else { return nil }
        return rect.minY - clip.bounds.minY
    }

    /// Scrolls so `row` starts `offset` points below the top of the visible
    /// area, as far as the list's length allows.
    private func scroll(row: Int, toOffset offset: CGFloat) {
        guard let scrollView = tableView.enclosingScrollView else { return }
        let clip = scrollView.contentView
        var bounds = clip.bounds
        bounds.origin.y = tableView.rect(ofRow: row).minY - offset
        clip.setBoundsOrigin(clip.constrainBoundsRect(bounds).origin)
        scrollView.reflectScrolledClipView(clip)
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
            case let .agent(name): store.participantFilter.emptyText(for: name, isMe: name == store.index.me)
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
        [debugScopeBar()] + (noticeBar.isHidden ? [] : ["notices: \(noticeBar.text.replacingOccurrences(of: "\n", with: " / "))"])
            + (0..<tableView.numberOfRows).map { row in
            let cell = tableView.view(atColumn: 0, row: row, makeIfNecessary: false)
            let selected = tableView.selectedRow == row ? "*" : " "
            return "row\(selected) \(dataSource.itemIdentifier(forRow: row) ?? "?") | \(cell?.accessibilityLabel() ?? "?")"
        } + ["emptyLabel: \(emptyLabel.isHidden ? "hidden" : emptyLabel.stringValue)"]
    }

    /// "scope: hidden", or the segments with the selected one starred and
    /// their tooltips.
    func debugScopeBar() -> String {
        guard !scopeBar.isHidden else { return "scope: hidden" }
        let segments = (0..<scopeControl.segmentCount).map { i in
            (scopeControl.label(forSegment: i) ?? "?") + (scopeControl.selectedSegment == i ? "*" : "")
        }
        let tips = (0..<scopeControl.segmentCount).map { scopeControl.toolTip(forSegment: $0) ?? "" }
        return "scope: \(segments.joined(separator: " ")) | tips: \(tips.joined(separator: " / "))"
    }
    #endif

    // MARK: NSTableViewDelegate

    func tableViewSelectionDidChange(_ notification: Notification) {
        guard !applying else { return }
        let row = tableView.selectedRow
        let id = row >= 0 ? dataSource.itemIdentifier(forRow: row) : nil
        if id == Self.draftRow {
            store.showDraft()
        } else {
            store.select(threadID: id)
        }
    }

    // MARK: Context menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let row = tableView.clickedRow
        guard row >= 0, let id = dataSource.itemIdentifier(forRow: row) else { return }
        func add(_ title: String, _ action: Selector, enabled: Bool = true) {
            let i = NSMenuItem(title: title, action: enabled ? action : nil, keyEquivalent: "")
            i.target = self
            i.representedObject = id
            menu.addItem(i)
        }
        if id == Self.draftRow {
            add("Discard Message…", #selector(contextDiscard(_:)))
            return
        }
        guard let t = store.index.thread(id) else { return }
        add("Reply", #selector(contextReply(_:)))
        add("Forward", #selector(contextForward(_:)))
        add("Close Thread", #selector(contextClose(_:)), enabled: t.isOpen)
        menu.addItem(.separator())
        add("Copy Thread ID", #selector(contextCopyID(_:)))
        add("Copy Thread Number", #selector(contextCopyNumber(_:)), enabled: t.number != nil)
        add("Show in Finder", #selector(contextReveal(_:)))
    }

    var onContextReply: (String) -> Void = { _ in }
    var onContextForward: (String) -> Void = { _ in }
    var onContextClose: (String) -> Void = { _ in }
    var onContextDiscard: () -> Void = {}

    @objc private func contextReply(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        onContextReply(id)
    }

    @objc private func contextForward(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        onContextForward(id)
    }

    @objc private func contextDiscard(_ sender: NSMenuItem) {
        onContextDiscard()
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

/// What needs the user about a participant, above its threads: a warning
/// sign and one line per notice (selectable, so a branch or a folder can be
/// copied) on a light orange band, with a separator under it.
final class NoticeBar: NSView {
    private let icon = NSImageView()
    private let label = NSTextField(wrappingLabelWithString: "")

    init() {
        super.init(frame: .zero)
        wantsLayer = true
        icon.image = NSImage(systemSymbolName: "exclamationmark.triangle.fill", accessibilityDescription: "Warning")?
            .withSymbolConfiguration(.init(pointSize: 12, weight: .regular).applying(.preferringMulticolor()))
        icon.setContentHuggingPriority(.required, for: .horizontal)
        label.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        label.textColor = .labelColor
        label.isSelectable = true
        label.setAccessibilityLabel("Warnings")
        let separator = NSBox()
        separator.boxType = .separator
        for v in [icon, label, separator] as [NSView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            addSubview(v)
        }
        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            icon.firstBaselineAnchor.constraint(equalTo: label.firstBaselineAnchor),
            label.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 6),
            label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            label.topAnchor.constraint(equalTo: topAnchor, constant: 8),
            label.bottomAnchor.constraint(equalTo: separator.topAnchor, constant: -8),
            separator.leadingAnchor.constraint(equalTo: leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: trailingAnchor),
            separator.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    var text: String { label.stringValue }

    /// One paragraph per notice, a little apart.
    func show(_ notices: [AgentNotice]) {
        let text = notices.map(\.text).joined(separator: "\n")
        guard label.stringValue != text else { return }
        let paragraph = NSMutableParagraphStyle()
        paragraph.paragraphSpacing = 4
        label.attributedStringValue = NSAttributedString(string: text, attributes: [
            .font: NSFont.systemFont(ofSize: NSFont.smallSystemFontSize), .foregroundColor: NSColor.labelColor,
            .paragraphStyle: paragraph,
        ])
    }

    override var wantsUpdateLayer: Bool { true }

    override func updateLayer() {
        layer?.backgroundColor = NSColor.systemOrange.withAlphaComponent(0.1).cgColor
    }

    /// Wraps at the width it was given.
    override func layout() {
        super.layout()
        if label.preferredMaxLayoutWidth != label.frame.width {
            label.preferredMaxLayoutWidth = label.frame.width
            needsLayout = true
        }
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

/// The draft row: a pencil, the subject (or "New Message"), and whom it is
/// to (or "Draft"), laid out like a thread row.
final class DraftCellView: NSTableCellView {
    private let icon = NSImageView()
    private let title = NSTextField.label()
    private let detail = NSTextField.label(color: .secondaryLabelColor)

    init() {
        super.init(frame: .zero)
        for v in [icon, title, detail] as [NSView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            addSubview(v)
        }
        textField = title
        imageView = icon
        title.font = .systemFont(ofSize: NSFont.systemFontSize, weight: .semibold)
        title.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        detail.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        icon.image = NSImage(systemSymbolName: "square.and.pencil", accessibilityDescription: "Draft")?
            .withSymbolConfiguration(.init(pointSize: 13, weight: .regular))
        icon.contentTintColor = .controlAccentColor
        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 0),
            icon.centerYAnchor.constraint(equalTo: centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 16),
            title.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 4),
            title.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -4),
            title.topAnchor.constraint(equalTo: topAnchor, constant: 7),
            detail.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            detail.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -4),
            detail.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 3),
        ])
        setAccessibilityElement(true)
        setAccessibilityRole(.cell)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    /// A selected row is drawn in the accent colour: the pencil turns white.
    override var backgroundStyle: NSView.BackgroundStyle {
        didSet { icon.contentTintColor = backgroundStyle == .emphasized ? .alternateSelectedControlTextColor : .controlAccentColor }
    }

    func configure(_ draft: NewThreadDraft) {
        title.stringValue = draft.rowTitle
        detail.stringValue = draft.rowDetail
        setAccessibilityLabel("Draft, \(draft.rowTitle), \(draft.rowDetail)")
        toolTip = draft.forward == nil ? "A new message, not sent yet" : "A forward, not sent yet"
    }
}

/// One thread: its flow (`A → B → A`, whose turn it is last) and age on top,
/// subject and a state icon below (Your turn, Undelivered, Closed, On duty),
/// in a fixed slot so subjects line up whether or not a row has one.
final class ThreadCellView: NSTableCellView {
    private let dot = NSImageView()
    private let parties = NSTextField.label()
    private let age = NSTextField.label(font: .monospacedDigitSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular),
                                        color: .secondaryLabelColor)
    private let subject = NSTextField.label(color: .secondaryLabelColor)
    private let stateIcon = NSImageView()
    private var unread = false
    private var isOpen = true
    private var flow = FlowLabel([])
    private var state: RowState?

    /// A row's state: what it says, its symbol and tint.
    struct RowState: Equatable {
        var label: String
        var detail: String
        var symbol: String
        var color: NSColor
        var pointSize: CGFloat = 12

        static func of(_ t: ThreadSummary, me: String) -> RowState? {
            let owner = t.owner ?? "?"
            if t.isUndelivered {
                return RowState(label: "Undelivered", detail: "Nobody is listening for \(owner)",
                                symbol: "exclamationmark.circle", color: .systemOrange)
            }
            if t.isWaiting(on: me) {
                return RowState(label: "Your turn", detail: "Waiting on you", symbol: "arrowshape.turn.up.left.fill",
                                color: .controlAccentColor)
            }
            if !t.isOpen {
                return RowState(label: "Closed", detail: "The thread is closed", symbol: "checkmark.circle",
                                color: .secondaryLabelColor)
            }
            if t.listener == "on-duty" {
                return RowState(label: "On duty", detail: "\(owner) is on duty and will pick it up", symbol: "circle.fill",
                                color: .systemGreen, pointSize: 8)
            }
            return nil
        }
    }

    init() {
        super.init(frame: .zero)
        for v in [dot, parties, age, subject, stateIcon] as [NSView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            addSubview(v)
        }
        textField = subject
        age.alignment = .right
        age.setContentCompressionResistancePriority(.required, for: .horizontal)
        age.setContentHuggingPriority(.required, for: .horizontal)
        parties.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        subject.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        stateIcon.imageScaling = .scaleNone
        stateIcon.imageAlignment = .alignCenter
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
            stateIcon.leadingAnchor.constraint(equalTo: subject.trailingAnchor, constant: 6),
            stateIcon.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -3),
            stateIcon.centerYAnchor.constraint(equalTo: subject.centerYAnchor),
            stateIcon.widthAnchor.constraint(equalToConstant: 18),
            stateIcon.heightAnchor.constraint(equalToConstant: 16),
        ])
        setAccessibilityElement(true)
        setAccessibilityRole(.cell)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    /// A selected row is drawn in the accent colour: tints turn white there.
    override var backgroundStyle: NSView.BackgroundStyle {
        didSet {
            updateImages()
            updateFlow()
        }
    }

    /// The flow, names joined by arrows. In an open thread the last name,
    /// whose turn it is, is semibold so the eye lands on it, and the names
    /// before it are full strength while there is unread mail; a closed
    /// thread's flow is plain secondary text. Too long for the row, it is cut
    /// in the middle, so the last name stays. Rebuilt when the row's
    /// highlight changes: an attributed string's colours don't follow it.
    private func updateFlow() {
        let selected = backgroundStyle == .emphasized
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byTruncatingMiddle
        func style(_ color: NSColor, _ weight: NSFont.Weight = .regular) -> [NSAttributedString.Key: Any] {
            [.font: NSFont.systemFont(ofSize: NSFont.systemFontSize, weight: weight),
             .foregroundColor: selected ? NSColor.alternateSelectedControlTextColor : color,
             .paragraphStyle: paragraph]
        }
        let quiet = style(.secondaryLabelColor)
        let earlier = isOpen && unread ? style(.labelColor) : quiet
        let turn = isOpen ? style(.labelColor, .semibold) : quiet
        let text = NSMutableAttributedString()
        for (i, step) in flow.steps.enumerated() {
            if i > 0 { text.append(NSAttributedString(string: FlowLabel.arrow, attributes: quiet)) }
            switch step {
            case let .name(name):
                text.append(NSAttributedString(string: name, attributes: i == flow.steps.count - 1 ? turn : earlier))
            case .gap:
                text.append(NSAttributedString(string: FlowLabel.ellipsis, attributes: quiet))
            }
        }
        parties.attributedStringValue = text
    }

    private func updateImages() {
        let selected = backgroundStyle == .emphasized
        dot.image = unread ? NSImage.dot(selected ? .alternateSelectedControlTextColor : .controlAccentColor,
                                         label: "Unread") : nil
        guard let state else {
            stateIcon.image = nil
            return
        }
        let color = selected ? NSColor.alternateSelectedControlTextColor : state.color
        stateIcon.image = NSImage(systemSymbolName: state.symbol, accessibilityDescription: state.label)?
            .withSymbolConfiguration(.init(pointSize: state.pointSize, weight: .semibold)
                .applying(.init(paletteColors: [color])))
    }

    func configure(_ t: ThreadSummary, me: String, now: Date) {
        unread = t.unread > 0
        isOpen = t.isOpen
        flow = FlowLabel(t.shownFlow)
        let to = t.owner ?? "?"
        updateFlow()
        subject.stringValue = t.subject
        subject.textColor = unread ? .labelColor : .secondaryLabelColor
        let seconds = Int(now.timeIntervalSince(t.updated ?? t.created ?? now))
        age.stringValue = Formatting.age(seconds: seconds)

        state = RowState.of(t, me: me)
        updateImages()
        stateIcon.toolTip = state.map { "\($0.label): \($0.detail)" }
        stateIcon.setAccessibilityLabel(state?.label)

        var spoken = [String]()
        if unread { spoken.append("\(t.unread) unread") }
        if !flow.names.isEmpty { spoken.append(flow.spoken) }
        spoken.append(t.subject)
        spoken.append("\(Formatting.spokenAge(seconds: seconds)) ago")
        if let s = state?.label { spoken.append(s) }
        setAccessibilityLabel(spoken.joined(separator: ", "))
        toolTip = "#\(t.number.map(String.init) ?? "?") \(t.id)\n" + (flow.isShortened ? "\(flow.fullText)\n" : "")
            + "\(t.count) message\(t.count == 1 ? "" : "s"), " + (t.isOpen ? "waiting on \(to)" : "closed")
    }
}
