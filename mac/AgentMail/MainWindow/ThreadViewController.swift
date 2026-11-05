// The thread view: the whole thread rendered as one HTML document in a
// WKWebView (content JavaScript off, strict CSP, attachments served by a
// scheme handler, every click decided by LinkPolicy), the first message new
// to me (else the newest) scrolled into view, and below it a pane that writes
// in one of three modes:
// Reply, to whoever wrote last that isn't me (or the sender of the card
// whose Reply was clicked); Forward, which hands an open thread on to anyone
// else (`reply --to X`, To an editable combo box, Esc goes back to Reply);
// and the new-thread draft, shown as an empty thread: an editable subject
// heading, "No messages yet" (or, for a forward of a closed thread, the
// forwarded messages read-only), and the pane with To, Discard and Send.
// ⌘↩ or ⇧⌘D sends. The text size (View menu, pinch) is a page zoom kept in
// AppSettings; the reply box, in the message bodies' type, follows it.
import AgentMailKit
import AppKit
import Observation
import WebKit

final class ThreadViewController: NSViewController, WKNavigationDelegate, NSSplitViewDelegate, NSTextViewDelegate,
    NSComboBoxDelegate {
    /// What the pane below the thread writes.
    enum PaneMode: String {
        /// A reply to the reply recipient, shown as text.
        case reply
        /// The open thread handed on to someone else, chosen in To.
        case forward
        /// The new-thread draft: a new message, or a forward of a closed thread.
        case newThread
    }

    /// Whose text the editor holds.
    private enum Composing: Equatable {
        case thread(String)
        case newThread(UUID)
    }

    private let store: MailStore
    private var webView: ThreadWebView!
    private let schemeHandler = AttachmentSchemeHandler()
    private let split = NSSplitView()
    /// The rendered thread, or the draft's header over its preview.
    private let topPane = FilledView()
    private let replyPane = NSView()

    // The draft's header, in place of the rendered thread's own.
    private let draftHeader = NSStackView()
    private let subjectField = NSTextField()
    private let draftInfo = NSTextField.label(color: .secondaryLabelColor)
    private let noMessages = NSTextField.label("No messages yet", font: .systemFont(ofSize: NSFont.systemFontSize + 4),
                                               color: .tertiaryLabelColor)
    private var webTopToPane: NSLayoutConstraint!
    private var webTopToHeader: NSLayoutConstraint!
    private var headerTop: NSLayoutConstraint!
    private var headerMaxWidth: NSLayoutConstraint!
    private var headerFill: NSLayoutConstraint!
    private var headerInset: NSLayoutConstraint!

    // The pane.
    private let recipientLabel = NSTextField.label(font: .systemFont(ofSize: NSFont.smallSystemFontSize, weight: .medium))
    private let toField = NSComboBox()
    private let sendButton = NSButton(title: "Reply", target: nil, action: nil)
    private let closeButton = NSButton(title: "Close Thread", target: nil, action: nil)
    private let cancelButton = NSButton(title: "Cancel", target: nil, action: nil)
    private let statusLabel = NSTextField.label(font: .systemFont(ofSize: NSFont.smallSystemFontSize), color: .secondaryLabelColor)
    private let sendHint = NSTextField.label("⌘↩ to send", font: .systemFont(ofSize: NSFont.smallSystemFontSize),
                                             color: .tertiaryLabelColor)
    private var replyScroll: NSScrollView!
    private(set) var replyView: NSTextView!
    private let placeholder = NSTextField.label(font: .systemFont(ofSize: NSFont.systemFontSize + 4), color: .tertiaryLabelColor)
    private var observation: Task<Void, Never>?

    /// Whose text the editor holds; saved back when it changes.
    private var composing: Composing?
    /// The selected (open) thread is being handed on: Forward mode.
    private var forwarding = false
    /// The recipient a card's Reply chose for this thread's reply.
    private var replyOverride: String?
    /// Thread id and fingerprint of what the web view shows: a thread, or
    /// (fingerprint "preview|…") the messages a forward draft carries.
    private var rendered: (id: String, fingerprint: String)?
    /// Messages that were unread when first shown, by thread: they stay
    /// unfolded when the thread is rendered again this session.
    private var expandedFiles: [String: Set<String>] = [:]
    /// The zoom while a pinch is under way.
    private var pinchZoom: Double?
    private lazy var css: String = {
        guard let url = Bundle.main.url(forResource: "thread", withExtension: "css"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return s
    }()
    private let dateStyle = Date.FormatStyle(date: .abbreviated, time: .shortened)

    /// Hands files and links to other apps; the debug hooks record instead.
    var openURL: (URL) -> Bool = { NSWorkspace.shared.open($0) }
    var revealFiles: ([URL]) -> Void = { NSWorkspace.shared.activateFileViewerSelecting($0) }

    init(store: MailStore) {
        self.store = store
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func loadView() {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        config.websiteDataStore = .nonPersistent()
        config.suppressesIncrementalRendering = true
        config.setURLSchemeHandler(schemeHandler, forURLScheme: AttachmentScheme.scheme)
        webView = ThreadWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = false
        // Pinching sets the page zoom instead (ThreadWebView.onPinch).
        webView.allowsMagnification = false
        webView.pageZoom = store.settings.textZoom
        webView.onPinch = { [weak self] step, ended in self?.pinch(step, ended: ended) }
        webView.underPageBackgroundColor = .textBackgroundColor
        webView.setAccessibilityLabel("Thread")

        let (scroll, text) = PlainTextEditor.make(accessibilityLabel: "Reply")
        replyScroll = scroll
        replyView = text
        replyView.delegate = self
        text.onCommandReturn = { [weak self] in self?.sendReply() }
        buildTopPane()
        buildReplyPane()

        split.isVertical = false
        split.dividerStyle = .thin
        split.delegate = self
        split.addArrangedSubview(topPane)
        split.addArrangedSubview(replyPane)
        split.setHoldingPriority(.defaultLow, forSubviewAt: 0)
        split.setHoldingPriority(.defaultHigh, forSubviewAt: 1)
        split.autosaveName = "ThreadSplit"
        split.translatesAutoresizingMaskIntoConstraints = false

        let container = NSView()
        container.addSubview(split)
        placeholder.stringValue = "No Thread Selected"
        placeholder.alignment = .center
        container.addSubview(placeholder)
        NSLayoutConstraint.activate([
            split.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            split.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            // Below the toolbar: the window has a full-size content view, and
            // a web view doesn't inset itself the way scroll views do.
            split.topAnchor.constraint(equalTo: container.safeAreaLayoutGuide.topAnchor),
            split.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            placeholder.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            placeholder.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            replyPane.heightAnchor.constraint(greaterThanOrEqualToConstant: Self.minimumReplyHeight),
            topPane.heightAnchor.constraint(greaterThanOrEqualToConstant: 120),
        ])
        view = container
    }

    private static let minimumReplyHeight: CGFloat = 130
    /// The page's measure and padding at actual size (thread.css: `main`'s
    /// max-width, calc(38em + 50px) at 17px, and its padding), which the
    /// draft's header follows so its subject sits where a thread's does.
    private static let pageWidth: CGFloat = 696
    private static let pagePadding = (top: CGFloat(22), side: CGFloat(24))
    /// The message bodies' type at actual size (thread.css: `body`'s
    /// font-size and line-height), which the reply box follows.
    static let bodyType = (size: CGFloat(17), lineHeight: CGFloat(1.6))

    /// The web view, and over it the draft's header (subject, what goes
    /// along) and "No messages yet", shown in draft mode only.
    private func buildTopPane() {
        subjectField.isBordered = false
        subjectField.isBezeled = false
        subjectField.drawsBackground = false
        subjectField.focusRingType = .none
        subjectField.usesSingleLineMode = true
        subjectField.lineBreakMode = .byTruncatingTail
        subjectField.cell?.isScrollable = true
        subjectField.cell?.wraps = false
        subjectField.delegate = self
        subjectField.setAccessibilityLabel("Subject")
        draftInfo.maximumNumberOfLines = 2
        draftInfo.lineBreakMode = .byWordWrapping
        draftInfo.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        draftHeader.orientation = .vertical
        draftHeader.alignment = .leading
        draftHeader.spacing = 6
        draftHeader.edgeInsets = NSEdgeInsets(top: 0, left: 0, bottom: 4, right: 0)
        draftHeader.addArrangedSubview(subjectField)
        draftHeader.addArrangedSubview(draftInfo)
        draftHeader.isHidden = true
        noMessages.alignment = .center
        noMessages.isHidden = true

        let below = NSLayoutGuide()
        for v in [webView!, draftHeader, noMessages] as [NSView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            topPane.addSubview(v)
        }
        topPane.addLayoutGuide(below)
        webTopToPane = webView.topAnchor.constraint(equalTo: topPane.topAnchor)
        webTopToHeader = webView.topAnchor.constraint(equalTo: draftHeader.bottomAnchor)
        headerTop = draftHeader.topAnchor.constraint(equalTo: topPane.topAnchor)
        headerMaxWidth = draftHeader.widthAnchor.constraint(lessThanOrEqualToConstant: Self.pageWidth)
        headerFill = draftHeader.widthAnchor.constraint(equalTo: topPane.widthAnchor)
        headerFill.priority = .defaultHigh - 1
        headerInset = draftHeader.leadingAnchor.constraint(greaterThanOrEqualTo: topPane.leadingAnchor)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: topPane.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: topPane.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: topPane.bottomAnchor),
            webTopToPane,
            headerTop, headerMaxWidth, headerFill, headerInset,
            draftHeader.centerXAnchor.constraint(equalTo: topPane.centerXAnchor),
            subjectField.widthAnchor.constraint(equalTo: draftHeader.widthAnchor),
            draftInfo.widthAnchor.constraint(equalTo: draftHeader.widthAnchor),
            below.topAnchor.constraint(equalTo: draftHeader.bottomAnchor),
            below.bottomAnchor.constraint(equalTo: topPane.bottomAnchor),
            noMessages.centerXAnchor.constraint(equalTo: topPane.centerXAnchor),
            noMessages.centerYAnchor.constraint(equalTo: below.centerYAnchor, constant: -20),
            noMessages.leadingAnchor.constraint(greaterThanOrEqualTo: topPane.leadingAnchor, constant: 16),
        ])
        applyZoom()
    }

    /// The native views that follow the page's zoom: the draft's header and
    /// the reply box.
    private func applyZoom() {
        applyHeaderZoom()
        applyEditorZoom()
    }

    /// The draft's header at the page's zoom, so it lines up with the
    /// rendered cards below it.
    private func applyHeaderZoom() {
        let z = CGFloat(webView.pageZoom)
        let subjectFont = NSFont.systemFont(ofSize: (27 * z).rounded(), weight: .bold)
        subjectField.font = subjectFont
        subjectField.placeholderAttributedString = NSAttributedString(string: "Subject", attributes: [
            .font: subjectFont, .foregroundColor: NSColor.placeholderTextColor,
        ])
        draftInfo.font = .systemFont(ofSize: (14 * z).rounded())
        headerTop.constant = Self.pagePadding.top * z
        headerMaxWidth.constant = Self.pageWidth * z
        headerFill.constant = -2 * Self.pagePadding.side * z
        headerInset.constant = Self.pagePadding.side * z
    }

    /// The reply box in the type of the message bodies at the page's zoom:
    /// the system font at their size, lines as far apart as theirs.
    private func applyEditorZoom() {
        let font = NSFont.systemFont(ofSize: Self.bodyType.size * CGFloat(webView.pageZoom))
        let paragraph = NSMutableParagraphStyle()
        // CSS spreads its line height around the glyphs; here the extra
        // goes between lines, so the caret keeps the text's height.
        paragraph.lineSpacing = max(0, Self.bodyType.lineHeight * font.pointSize
            - (font.ascender - font.descender + font.leading))
        replyView.font = font
        replyView.defaultParagraphStyle = paragraph
        replyView.typingAttributes[.font] = font
        replyView.typingAttributes[.paragraphStyle] = paragraph
        if let storage = replyView.textStorage, storage.length > 0 {
            storage.addAttributes([.font: font, .paragraphStyle: paragraph],
                                  range: NSRange(location: 0, length: storage.length))
        }
    }

    /// To and status on top, the text, then the hint and the buttons:
    /// Cancel (Forward mode), Close Thread or Discard, and Send.
    private func buildReplyPane() {
        let toLabel = NSTextField.label("To:", color: .secondaryLabelColor)
        recipientLabel.setAccessibilityLabel("Recipient")
        toField.translatesAutoresizingMaskIntoConstraints = false
        toField.controlSize = .small
        toField.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        toField.completes = true
        toField.usesDataSource = false
        toField.delegate = self
        toField.target = self
        toField.action = #selector(toCommitted(_:))
        toField.setAccessibilityLabel("To")
        toField.isHidden = true
        toField.widthAnchor.constraint(equalToConstant: 180).isActive = true
        let top = NSStackView(views: [toLabel, recipientLabel, toField, statusLabel, NSView()])
        top.orientation = .horizontal
        top.spacing = 8
        top.edgeInsets = NSEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        statusLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        cancelButton.target = self
        cancelButton.action = #selector(cancelClicked(_:))
        cancelButton.bezelStyle = .push
        cancelButton.toolTip = "Back to Reply (Esc in To); your text stays"
        cancelButton.isHidden = true
        closeButton.target = self
        closeButton.action = #selector(closeThreadClicked(_:))
        closeButton.bezelStyle = .push
        closeButton.imagePosition = .imageLeading
        sendButton.target = self
        sendButton.action = #selector(sendClicked(_:))
        sendButton.bezelStyle = .push
        sendButton.bezelColor = .controlAccentColor
        sendButton.keyEquivalent = ""
        sendButton.imagePosition = .imageLeading
        sendHint.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let bottom = NSStackView(views: [sendHint, NSView(), cancelButton, closeButton, sendButton])
        bottom.orientation = .horizontal
        bottom.spacing = 8
        bottom.edgeInsets = NSEdgeInsets(top: 6, left: 12, bottom: 8, right: 10)

        let separator = NSBox()
        separator.boxType = .separator
        for v in [top, separator, replyScroll!, bottom] as [NSView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            replyPane.addSubview(v)
        }
        top.setHuggingPriority(.defaultHigh, for: .vertical)
        bottom.setHuggingPriority(.defaultHigh, for: .vertical)
        NSLayoutConstraint.activate([
            top.leadingAnchor.constraint(equalTo: replyPane.leadingAnchor),
            top.trailingAnchor.constraint(equalTo: replyPane.trailingAnchor),
            top.topAnchor.constraint(equalTo: replyPane.topAnchor),
            separator.leadingAnchor.constraint(equalTo: replyPane.leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: replyPane.trailingAnchor),
            separator.topAnchor.constraint(equalTo: top.bottomAnchor),
            replyScroll.leadingAnchor.constraint(equalTo: replyPane.leadingAnchor),
            replyScroll.trailingAnchor.constraint(equalTo: replyPane.trailingAnchor),
            replyScroll.topAnchor.constraint(equalTo: separator.bottomAnchor),
            replyScroll.bottomAnchor.constraint(equalTo: bottom.topAnchor),
            bottom.leadingAnchor.constraint(equalTo: replyPane.leadingAnchor),
            bottom.trailingAnchor.constraint(equalTo: replyPane.trailingAnchor),
            bottom.bottomAnchor.constraint(equalTo: replyPane.bottomAnchor),
        ])
        configurePane(for: .reply, forwardDraft: false)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        update()
        let changes = Observations { [store] in
            (store.selectedThreadID, store.detail, store.detailError, store.busyThreadIDs, store.index,
             store.newDraft, store.isShowingDraft, store.isSendingDraft, store.forwardSource, store.forwardSourceError)
        }
        observation = Task { [weak self] in
            for await _ in changes { self?.update() }
        }
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        let h = replyPane.frame.height
        if split.subviews.count == 2, h < Self.minimumReplyHeight || h > split.bounds.height * 0.45 {
            split.setPosition(max(split.bounds.height - 200, 120), ofDividerAt: 0)
        }
    }

    // MARK: State

    private var me: String { store.index.me }

    /// What the pane writes now: what the editor holds, which follows the
    /// store on the next update.
    var paneMode: PaneMode {
        if case .newThread? = composing { return .newThread }
        return forwarding ? .forward : .reply
    }

    private func update() {
        if store.isShowingDraft, let draft = store.newDraft {
            updateDraft(draft)
            return
        }
        let id = store.selectedThreadID
        switchComposing(to: id.map(Composing.thread))
        showDraftHeader(false)
        let hasThread = id != nil
        split.isHidden = !hasThread
        placeholder.isHidden = hasThread
        configurePane(for: paneMode, forwardDraft: false)
        if !hasThread {
            placeholder.stringValue = "No Thread Selected"
            rendered = nil
            sendButton.isEnabled = false
            closeButton.isEnabled = false
            return
        }

        let summary = id.flatMap { store.index.thread($0) }
        let detail = store.detail?.id == id ? store.detail : nil
        if let detail {
            if rendered?.id != detail.id || rendered?.fingerprint != renderKey(detail) {
                render(detail)
            }
        } else if rendered?.id != id {
            rendered = nil
            webView.loadHTMLString("", baseURL: nil)
        }

        if let error = store.detailError, detail == nil {
            statusLabel.stringValue = error.errorDescription ?? "Couldn’t load the thread"
        } else if detail == nil {
            statusLabel.stringValue = "Loading…"
        } else if forwarding {
            statusLabel.stringValue = forwardStatus
        } else {
            statusLabel.stringValue = ""
        }

        recipientLabel.stringValue = replyRecipient ?? "—"
        if forwarding { setComboItems(forwardCandidates(summary: summary, detail: detail)) }
        let busy = id.map { store.busyThreadIDs.contains($0) } ?? false
        let open = detail?.isOpen ?? summary?.isOpen ?? false
        closeButton.isEnabled = open && !busy
        cancelButton.isEnabled = !busy
        sendButton.isEnabled = canSendReply
        replyView.isEditable = !busy
        toField.isEnabled = !busy
        if busy { statusLabel.stringValue = "Sending…" }
    }

    /// Forward mode's status line: what handing the thread on does, or what
    /// is wrong with the name typed.
    private var forwardStatus: String {
        let to = toField.stringValue.trimmingCharacters(in: .whitespaces)
        if to.isEmpty || Formatting.isValidRecipient(to, me: me) {
            return "They see the whole thread, and it becomes their turn"
        }
        return to == me ? "That’s you" : "A name has no spaces or slashes"
    }

    /// The draft on screen: its header and preview, the pane with To,
    /// Discard and Send.
    private func updateDraft(_ draft: NewThreadDraft) {
        switchComposing(to: .newThread(draft.id))
        showDraftHeader(true)
        split.isHidden = false
        placeholder.isHidden = true
        configurePane(for: .newThread, forwardDraft: draft.forward != nil)
        setComboItems(store.index.agents.map(\.name))

        if let source = draft.forward {
            let loaded = store.forwardSource?.id == source.threadID ? store.forwardSource : nil
            draftInfo.stringValue = Forwarding.description(of: source, thread: loaded,
                                                           summary: store.index.thread(source.threadID), me: me) {
                [dateStyle] in $0.formatted(dateStyle)
            }
            noMessages.isHidden = true
            webView.isHidden = false
            if let loaded {
                if rendered?.id != loaded.id || rendered?.fingerprint != previewKey(loaded, message: source.message) {
                    renderPreview(loaded, message: source.message)
                }
            } else if rendered != nil {
                rendered = nil
                webView.loadHTMLString("", baseURL: nil)
            }
        } else {
            let to = draft.recipient
            draftInfo.stringValue = Formatting.isValidName(to) ? "New thread to \(to)" : "New thread"
            noMessages.isHidden = false
            webView.isHidden = true
            if rendered != nil {
                rendered = nil
                webView.loadHTMLString("", baseURL: nil)
            }
        }

        let to = draft.recipient
        if store.isSendingDraft {
            statusLabel.stringValue = "Sending…"
        } else if let error = store.forwardSourceError, draft.forward != nil, store.forwardSource == nil {
            statusLabel.stringValue = error.errorDescription ?? "Couldn’t load the forwarded thread"
        } else if !to.isEmpty, !Formatting.isValidName(to) {
            statusLabel.stringValue = "A name has no spaces or slashes"
        } else if to == me {
            statusLabel.stringValue = "That’s you"
        } else {
            statusLabel.stringValue = ""
        }
        let sending = store.isSendingDraft
        sendButton.isEnabled = canSendDraft
        closeButton.isEnabled = !sending
        replyView.isEditable = !sending
        subjectField.isEditable = !sending
        toField.isEnabled = !sending
    }

    /// The draft's header over the web view, or the web view alone.
    private func showDraftHeader(_ shown: Bool) {
        guard draftHeader.isHidden == shown else { return }
        draftHeader.isHidden = !shown
        if shown {
            webTopToPane.isActive = false
            webTopToHeader.isActive = true
        } else {
            webTopToHeader.isActive = false
            webTopToPane.isActive = true
            noMessages.isHidden = true
            webView.isHidden = false
        }
    }

    /// Titles, symbols and which controls show, for each mode.
    private func configurePane(for mode: PaneMode, forwardDraft: Bool) {
        recipientLabel.isHidden = mode != .reply
        toField.isHidden = mode == .reply
        cancelButton.isHidden = mode != .forward
        closeButton.isHidden = mode == .forward
        switch mode {
        case .reply:
            setButton(closeButton, "Close Thread", "checkmark.circle",
                      "Close the thread (⌃⌘A). Text in the reply box becomes the closing note.")
            setButton(sendButton, "Reply", "paperplane.fill", "Send the reply to \(replyRecipient ?? "the sender") (⌘↩ or ⇧⌘D)")
            replyView.setAccessibilityLabel("Reply")
        case .forward:
            setButton(sendButton, "Forward", "arrowshape.turn.up.right.fill",
                      "Hand the thread on with your note: the recipient sees all of it (⌘↩ or ⇧⌘D)")
            toField.placeholderString = "Forward to…"
            replyView.setAccessibilityLabel("Note")
        case .newThread:
            setButton(closeButton, "Discard", "trash", "Discard this message")
            if forwardDraft {
                setButton(sendButton, "Forward", "arrowshape.turn.up.right.fill",
                          "Send the forward: your note, then the forwarded messages (⌘↩ or ⇧⌘D)")
            } else {
                setButton(sendButton, "Send", "paperplane.fill", "Send the new thread (⌘↩ or ⇧⌘D)")
            }
            toField.placeholderString = "Recipient"
            subjectField.toolTip = forwardDraft
                ? "Optional: without a subject, “Fwd:” and the forwarded thread’s subject"
                : "Optional: without a subject, the first line of the message is used"
            replyView.setAccessibilityLabel(forwardDraft ? "Note" : "Message")
        }
    }

    /// The symbol each button shows, so an update only sets what changed.
    private var buttonSymbols: [ObjectIdentifier: String] = [:]

    private func setButton(_ button: NSButton, _ title: String, _ symbol: String, _ tip: String) {
        if button.title != title { button.title = title }
        if button.toolTip != tip { button.toolTip = tip }
        if buttonSymbols[ObjectIdentifier(button)] != symbol {
            buttonSymbols[ObjectIdentifier(button)] = symbol
            button.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        }
    }

    private func setComboItems(_ names: [String]) {
        if (toField.objectValues as? [String]) != names {
            toField.removeAllItems()
            toField.addItems(withObjectValues: names)
        }
    }

    /// Whom a reply goes to: a card's choice, else whoever wrote last that
    /// isn't me (the CLI's default).
    private var replyRecipient: String? {
        guard let id = store.selectedThreadID else { return nil }
        if let replyOverride { return replyOverride }
        let detail = store.detail?.id == id ? store.detail : nil
        return detail?.replyRecipient(me: me) ?? store.index.thread(id)?.replyRecipient(me: me)
    }

    /// Whom Forward mode offers: every repo, and anyone else in the thread.
    private func forwardCandidates(summary: ThreadSummary?, detail: ThreadDetail?) -> [String] {
        let inThread = detail?.participants ?? summary?.participants ?? []
        return Set(store.index.agents.map(\.name) + inThread).subtracting([me])
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    /// The editor's text moves with the selection: each thread keeps its own
    /// unsent reply, and the draft its body. Forward mode and a card's choice
    /// of recipient end with it.
    private func switchComposing(to key: Composing?) {
        guard key != composing else { return }
        saveComposing()
        composing = key
        forwarding = false
        replyOverride = nil
        switch key {
        case let .thread(id)?:
            replyView.string = store.drafts[id] ?? ""
        case .newThread?:
            let draft = store.newDraft
            replyView.string = draft?.body ?? ""
            subjectField.stringValue = draft?.subject ?? ""
            toField.stringValue = draft?.to ?? ""
        case nil:
            replyView.string = ""
        }
        replyView.undoManager?.removeAllActions(withTarget: replyView.textStorage as Any)
    }

    private func saveComposing() {
        let text = replyView.string
        switch composing {
        case let .thread(id)?:
            store.drafts[id] = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
        case let .newThread(draftID)?:
            if store.newDraft?.id == draftID { store.editDraft { $0.body = text } }
        case nil:
            break
        }
    }

    /// Puts text into the reply box of `id` (a failed notification reply).
    func restoreDraft(_ text: String, for id: String) {
        store.drafts[id] = text
        if composing == .thread(id) {
            replyView.string = text
            view.window?.makeFirstResponder(replyView)
        }
    }

    /// Keeps what the editor holds: the thread's unsent reply, or the draft's
    /// body (the window is closing, or a draft is about to be replaced).
    func saveDraft() {
        saveComposing()
    }

    #if DEBUG
    func debugState() -> [String] {
        let to = paneMode == .reply ? "to=\(recipientLabel.stringValue)"
            : "to=\(toField.stringValue.debugDescription) items=\(toField.objectValues.compactMap { $0 as? String })"
        func button(_ b: NSButton) -> String { b.isHidden ? "hidden" : "\(b.title)(\(b.isEnabled ? "on" : "off"))" }
        let header = draftHeader.isHidden ? "hidden"
            : "subject=\(subjectField.stringValue.debugDescription) "
                + "placeholder=\(subjectField.placeholderAttributedString?.string ?? "") "
                + "info=\(draftInfo.stringValue.debugDescription) noMessages=\(noMessages.isHidden ? "hidden" : "shown") "
                + "web=\(webView.isHidden ? "hidden" : "shown")"
        return [
            "thread.placeholder: \(placeholder.isHidden ? "hidden" : placeholder.stringValue)",
            "thread.rendered: \(rendered.map { "\($0.id) \($0.fingerprint)" } ?? "-")",
            "thread.mode: \(paneMode.rawValue) \(to)",
            "thread.draftHeader: \(header)",
            "thread.status: \(statusLabel.stringValue)",
            "thread.buttons: send=\(button(sendButton)) close=\(button(closeButton)) cancel=\(button(cancelButton))",
            "thread.reply: \(replyView.string.debugDescription) label=\(replyView.accessibilityLabel() ?? "") "
                + "smartQuotes=\(replyView.isAutomaticQuoteSubstitutionEnabled) "
                + "spelling=\(replyView.isContinuousSpellCheckingEnabled) undo=\(replyView.allowsUndo)",
            "thread.zoom: page=\(webView.pageZoom) setting=\(store.settings.textZoom)",
            "thread.replyFont: \(replyFontDescription)",
            "thread.expanded: \(expandedFiles[rendered?.id ?? ""].map { $0.sorted() } ?? [])",
            "thread.schemeLog: \(schemeHandler.log.suffix(12))",
            "thread.opened: \(debugOpened)",
        ]
    }

    /// The reply box's type: its text's font and line spacing at the start
    /// and end ("same" when they agree), or when empty what typing gets.
    private var replyFontDescription: String {
        func describe(_ attrs: [NSAttributedString.Key: Any]) -> String {
            let font = attrs[.font] as? NSFont
            let spacing = (attrs[.paragraphStyle] as? NSParagraphStyle)?.lineSpacing ?? 0
            return "\(font?.familyName ?? "?") \(font?.pointSize ?? 0) lineSpacing=\((spacing * 10).rounded() / 10)"
        }
        guard let storage = replyView.textStorage, storage.length > 0 else {
            return describe(replyView.typingAttributes) + " (typing)"
        }
        let first = describe(storage.attributes(at: 0, effectiveRange: nil))
        let last = describe(storage.attributes(at: storage.length - 1, effectiveRange: nil))
        return first + (first == last ? "" : ", last \(last)")
    }

    /// The thread's web view, for the debug hooks' scripts and clicks.
    var debugWebView: WKWebView { webView }

    /// Files and links the view handed to other apps, once recording.
    private(set) var debugOpened: [String] = []

    /// Records opens instead of launching other apps.
    func debugRecordOpens() {
        openURL = { [weak self] url in
            self?.debugOpened.append("open \(url.isFileURL ? url.lastPathComponent : url.absoluteString)")
            return true
        }
        revealFiles = { [weak self] urls in self?.debugOpened.append("reveal \(urls.map(\.lastPathComponent))") }
    }

    /// A whole pinch of `step`, as if the fingers had lifted.
    func debugPinch(_ step: Double) { pinch(step, ended: true) }
    #endif

    // MARK: Rendering

    /// The thread this one forwards and the forwards of it, for the header.
    private func related(_ detail: ThreadDetail) -> (from: ThreadSummary?, forwards: [ThreadSummary]) {
        let index = store.index
        let source = (detail.forwardedFrom ?? index.thread(detail.id)?.forwardedFrom).map {
            index.thread($0) ?? ThreadSummary(id: $0, subject: "")
        }
        return (source, index.forwards(of: detail.id))
    }

    /// What the rendered thread depends on: its content, and the related
    /// threads its header links to.
    private func renderKey(_ detail: ThreadDetail) -> String {
        let (source, forwards) = related(detail)
        let links = ([source].compactMap { $0 } + forwards).map { "\($0.id)#\($0.number ?? 0)\($0.firstRecipient ?? "")" }
        return detail.contentFingerprint + "|" + links.joined(separator: ",")
    }

    private func previewKey(_ thread: ThreadDetail, message: String?) -> String {
        "preview|\(message ?? "*")|\(thread.contentFingerprint)"
    }

    /// The thread with what is new to me marked unread (the view scrolls to
    /// the first of it), and what was new earlier this session unfolded.
    private func render(_ detail: ThreadDetail) {
        let unread = store.index.thread(detail.id).map { detail.unreadFiles(me: me, summary: $0) } ?? []
        let expanded = expandedFiles[detail.id, default: []].union(unread)
        expandedFiles[detail.id] = expanded
        let files = store.filesDirectory(detail.id)
        schemeHandler.thread = (detail.id, files)
        let (source, forwards) = related(detail)
        let html = ThreadDocument.html(thread: detail, me: me, css: css + Self.systemColorsCSS(), unread: unread,
                                       expanded: expanded, forwardedFrom: source, forwards: forwards,
                                       readAttachment: { Self.readText(named: $0, in: files) }) { [dateStyle] in
            $0.formatted(dateStyle)
        }
        rendered = (detail.id, renderKey(detail))
        webView.loadHTMLString(html, baseURL: nil)
    }

    /// The messages a forward draft carries, read-only below its header.
    private func renderPreview(_ thread: ThreadDetail, message: String?) {
        let files = store.filesDirectory(thread.id)
        schemeHandler.thread = (thread.id, files)
        let html = ThreadDocument.forwardPreview(thread: thread, message: message, me: me,
                                                 css: css + Self.systemColorsCSS(),
                                                 readAttachment: { Self.readText(named: $0, in: files) }) { [dateStyle] in
            $0.formatted(dateStyle)
        }
        rendered = (thread.id, previewKey(thread, message: message))
        webView.loadHTMLString(html, baseURL: nil)
    }

    /// The accent and link colours, which WebKit doesn't take from the
    /// system (its AccentColor is always blue), for light and dark.
    private static func systemColorsCSS() -> String {
        func css(_ color: NSColor, _ name: NSAppearance.Name) -> String {
            var out = ""
            NSAppearance(named: name)?.performAsCurrentDrawingAppearance {
                let c = color.usingColorSpace(.sRGB) ?? color
                out = "rgb(\(Int(c.redComponent * 255)) \(Int(c.greenComponent * 255)) \(Int(c.blueComponent * 255)))"
            }
            return out
        }
        func vars(_ name: NSAppearance.Name) -> String {
            ":root { --accent: \(css(.controlAccentColor, name)); --link: \(css(.linkColor, name)); }"
        }
        return "\n\(vars(.aqua))\n@media (prefers-color-scheme: dark) { \(vars(.darkAqua)) }\n"
    }

    /// A Markdown attachment's text, unless it is too big to show inline.
    private static func readText(named name: String, in files: URL) -> String? {
        guard let url = AttachmentScheme.file(named: name, in: files),
              let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size <= 256_000 else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // A forward's preview stays at its top.
        guard let rendered, !rendered.fingerprint.hasPrefix("preview|") else { return }
        // App-injected script; runs even with content JavaScript disabled.
        // The first unread message, else the newest; the top when that is the
        // first message, so the subject shows.
        let js = "var n = document.querySelector('article.unread') || document.getElementById('newest');"
            + " if (n && n.previousElementSibling && n.previousElementSibling.matches('article')) {"
            + " n.scrollIntoView({block: 'start'}); } else { window.scrollTo(0, 0); } !!n"
        webView.evaluateJavaScript(js) { result, error in
            if let error {
                Timing.note("scroll to newest failed: \(error.localizedDescription)")
            } else {
                Timing.note("scrolled to newest: \(String(describing: result))")
            }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        let link = LinkPolicy.classify(action.request.url, isLinkClick: action.navigationType == .linkActivated,
                                       thread: rendered?.id)
        Timing.note("link \(action.request.url?.absoluteString ?? "-"): \(link)")
        // In a forward's preview only attachments do anything.
        let previewing = paneMode == .newThread
        switch link {
        case .loadDocument: return .allow
        case let .openAttachment(name): openAttachment(name, reveal: false)
        case let .revealAttachment(name): openAttachment(name, reveal: true)
        case _ where previewing: break
        case let .openExternally(url): _ = openURL(url)
        case let .openMessage(file): openMessage(file)
        case let .reply(file):
            self.webView.refuseFocusBriefly()
            reply(toMessage: file)
        case let .forward(file):
            self.webView.refuseFocusBriefly()
            if let id = rendered?.id { forward(threadID: id, message: file) }
        case let .openThread(id): if !store.reveal(threadID: id) { NSSound.beep() }
        case .ignore: break
        }
        return .cancel
    }

    // MARK: Opening files

    private func openAttachment(_ name: String, reveal: Bool) {
        guard let id = rendered?.id, let file = AttachmentScheme.file(named: name, in: store.filesDirectory(id)),
              FileManager.default.fileExists(atPath: file.path) else {
            NSSound.beep()
            return
        }
        // Executables, scripts and apps are shown, never run.
        if reveal || !LinkPolicy.isSafeToOpen(file) || !openURL(file) {
            revealFiles([file])
        }
    }

    /// The message's file in the default app for Markdown.
    private func openMessage(_ file: String) {
        guard let id = rendered?.id, store.detail?.msgs.contains(where: { $0.file == file }) == true else { return }
        let url = store.threadDirectory(id).appending(path: "msgs").appending(path: file)
        guard FileManager.default.fileExists(atPath: url.path) else {
            NSSound.beep()
            return
        }
        if !openURL(url) { revealFiles([url]) }
    }

    // MARK: Text size

    var textZoom: Double { store.settings.textZoom }

    func setTextZoom(_ zoom: Double) {
        let z = TextZoom.clamp(zoom)
        store.settings.textZoom = z
        webView.pageZoom = z
        applyZoom()
    }

    func makeTextBigger() { setTextZoom(TextZoom.bigger(than: textZoom)) }
    func makeTextSmaller() { setTextZoom(TextZoom.smaller(than: textZoom)) }
    func makeTextActualSize() { setTextZoom(TextZoom.actualSize) }

    /// Live while pinching, saved when the fingers lift.
    private func pinch(_ step: Double, ended: Bool) {
        let z = TextZoom.clamp((pinchZoom ?? webView.pageZoom) * (1 + step))
        pinchZoom = z
        webView.pageZoom = z
        applyZoom()
        if ended {
            pinchZoom = nil
            setTextZoom(z)
        }
    }

    // MARK: Actions

    /// Whom Send writes to in Reply and Forward mode.
    private var threadRecipient: String? {
        switch paneMode {
        case .reply: replyRecipient
        case .forward: toField.stringValue.trimmingCharacters(in: .whitespaces)
        case .newThread: nil
        }
    }

    /// The draft as it would be sent, with the editor's text as its body.
    private var draftAsShown: NewThreadDraft? {
        guard var draft = store.newDraft else { return nil }
        if composing == .newThread(draft.id) { draft.body = replyView.string }
        return draft
    }

    private var canSendDraft: Bool {
        guard let draft = draftAsShown, !store.isSendingDraft else { return false }
        return draft.canSend(me: me)
    }

    /// Send (⌘↩, ⇧⌘D): a recipient and a body (a forward draft's note is
    /// optional), and nothing on its way already.
    var canSendReply: Bool {
        if paneMode == .newThread { return canSendDraft }
        guard let id = store.selectedThreadID, !store.busyThreadIDs.contains(id),
              let to = threadRecipient, Formatting.isValidRecipient(to, me: me) else { return false }
        return !replyView.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var canCloseThread: Bool {
        guard let id = store.selectedThreadID, !store.busyThreadIDs.contains(id) else { return false }
        return (store.detail?.id == id ? store.detail?.isOpen : store.index.thread(id)?.isOpen) ?? false
    }

    func textDidChange(_ notification: Notification) {
        sendButton.isEnabled = canSendReply
    }

    func controlTextDidChange(_ notification: Notification) {
        guard let field = notification.object as? NSTextField else { return }
        if field === subjectField {
            store.editDraft { $0.subject = subjectField.stringValue }
        } else if field === toField {
            toChanged()
        }
    }

    func comboBoxSelectionDidChange(_ notification: Notification) {
        // The field takes the picked name after this returns.
        Task { [weak self] in self?.toChanged() }
    }

    /// To was edited: the draft's recipient, or Forward mode's.
    private func toChanged() {
        if paneMode == .newThread {
            store.editDraft { $0.to = toField.stringValue }
        } else if forwarding {
            statusLabel.stringValue = forwardStatus
            sendButton.isEnabled = canSendReply
        }
    }

    /// Esc in To leaves Forward mode; Return in the subject, or in To with a
    /// name, moves on to the body (To also commits the name as usual).
    func control(_ control: NSControl, textView: NSTextView, doCommandBy selector: Selector) -> Bool {
        if control === toField, selector == #selector(NSResponder.cancelOperation(_:)), forwarding {
            leaveForwardMode()
            return true
        }
        guard selector == #selector(NSResponder.insertNewline(_:)) else { return false }
        if control === subjectField, paneMode == .newThread {
            moveToBody()
            return true
        }
        if control === toField, paneMode != .reply, hasRecipientName {
            moveToBody()
        }
        return false
    }

    /// A name picked from To's list with the mouse (the click is in the
    /// list's own window) moves on to the body.
    @objc private func toCommitted(_ sender: Any?) {
        let event = NSApp.currentEvent
        guard paneMode != .reply, hasRecipientName, event?.type == .leftMouseUp, event?.window !== view.window
        else { return }
        moveToBody()
    }

    private var hasRecipientName: Bool {
        Formatting.isValidName(toField.stringValue.trimmingCharacters(in: .whitespaces))
    }

    /// After the field has finished with the Return (it selects its text
    /// again once it is done).
    private func moveToBody() {
        Task { [weak self] in
            guard let self else { return }
            view.window?.makeFirstResponder(replyView)
        }
    }

    /// ⌘R: focus the reply box, quoting what is selected in the thread
    /// (or, from a card's Reply button, what was selected before the click).
    /// Leaves Forward mode; in the draft it focuses the body.
    func focusReply(fallbackSelection: String = "") {
        update()
        if paneMode == .newThread {
            view.window?.makeFirstResponder(replyView)
            return
        }
        guard store.selectedThreadID != nil else { return }
        if forwarding {
            forwarding = false
            update()
        }
        view.window?.makeFirstResponder(replyView)
        webView.evaluateJavaScript("window.getSelection().toString()") { [weak self] result, _ in
            guard let self else { return }
            let current = result as? String ?? ""
            let quoted = Quoting.replyPrefill(selection: current.isEmpty ? fallbackSelection : current)
            guard !quoted.isEmpty else { return }
            replyView.insertText(quoted, replacementRange: replyView.selectedRange())
        }
    }

    /// A card's Reply: address the reply to that message's sender (or, for
    /// my own message, to whom I wrote), then as ⌘R.
    func reply(toMessage file: String?) {
        let selection = webView.selectionAtMouseDown
        if let file, let m = store.detail?.msgs.first(where: { $0.file == file }),
           let to = m.from == me ? m.to : m.from, to != me {
            replyOverride = to
            recipientLabel.stringValue = to
        }
        focusReply(fallbackSelection: selection)
        update()
    }

    /// Forward (toolbar, Message › Forward, the list's menu, a Forward
    /// button): an open thread is handed on where it is, in Forward mode; a
    /// closed one goes in a new thread, the draft, whole or only `message`.
    func forward(threadID id: String, message: String? = nil) {
        if store.isOpen(id) {
            if store.selectedThreadID != id { store.select(threadID: id) }
            update()
            enterForwardMode()
        } else {
            startForwardDraft(ForwardSource(threadID: id, message: message))
        }
    }

    /// To becomes a combo box, empty and focused; Send reads Forward.
    private func enterForwardMode() {
        guard store.selectedThreadID != nil else { return }
        if !forwarding {
            forwarding = true
            toField.stringValue = ""
            update()
        }
        view.window?.makeFirstResponder(toField)
    }

    /// Back to Reply (Cancel, Esc in To); the text stays.
    func leaveForwardMode() {
        guard forwarding else { return }
        forwarding = false
        update()
        view.window?.makeFirstResponder(replyView)
    }

    /// New Message: the draft (a new one, or the one there is), focused on
    /// To when it is empty, else on the body.
    func newMessage(to recipient: String?) {
        saveComposing()
        store.startNewMessage(to: recipient)
        showDraftNow()
    }

    /// A forward of a closed thread in place of any draft, asking first when
    /// that draft holds something typed.
    private func startForwardDraft(_ source: ForwardSource) {
        saveComposing()
        guard let existing = store.newDraft, existing.hasTypedContent, let window = view.window else {
            store.startForward(source)
            showDraftNow()
            return
        }
        let alert = NSAlert()
        alert.messageText = "Discard the message you’re writing?"
        alert.informativeText = "The forward takes its place. “\(existing.rowTitle)” hasn’t been sent."
        alert.addButton(withTitle: "Discard and Forward")
        alert.addButton(withTitle: "Cancel")
        alert.beginSheetModal(for: window) { [weak self] response in
            guard response == .alertFirstButtonReturn, let self else { return }
            store.startForward(source)
            showDraftNow()
        }
    }

    /// The draft on screen at once (not on the next observation): To focused
    /// when it is empty, else the body.
    private func showDraftNow() {
        update()
        let empty = store.newDraft?.recipient.isEmpty ?? true
        view.window?.makeFirstResponder(empty ? toField : replyView)
    }

    /// Discard: asks in a sheet when something was typed.
    func discardDraft() {
        update()
        guard paneMode == .newThread else { return }
        saveComposing()
        guard let draft = store.newDraft, draft.hasTypedContent, let window = view.window else {
            store.discardDraft()
            return
        }
        let alert = NSAlert()
        alert.messageText = "Discard this message?"
        alert.informativeText = "It hasn’t been sent."
        alert.addButton(withTitle: "Discard")
        alert.addButton(withTitle: "Cancel")
        alert.beginSheetModal(for: window) { [weak self] response in
            if response == .alertFirstButtonReturn { self?.store.discardDraft() }
        }
    }

    /// Space in the thread list: page down the thread.
    func pageDown() {
        webView.evaluateJavaScript("window.scrollBy(0, window.innerHeight * 0.9)", completionHandler: nil)
    }

    @objc private func sendClicked(_ sender: Any?) { sendReply() }
    @objc private func cancelClicked(_ sender: Any?) { leaveForwardMode() }

    @objc private func closeThreadClicked(_ sender: Any?) {
        if paneMode == .newThread { discardDraft() } else { closeThread() }
    }

    /// Send: the reply, the thread handed on, or the draft.
    func sendReply() {
        update()
        paneMode == .newThread ? sendDraft() : sendToThread()
    }

    private func sendToThread() {
        guard let id = store.selectedThreadID, canSendReply, let to = threadRecipient else {
            NSSound.beep()
            return
        }
        let body = replyView.string
        let handedOn = forwarding
        Task {
            do {
                try await store.reply(threadID: id, to: to, body: body)
                if composing == .thread(id) {
                    if replyView.string == body {
                        replyView.string = ""
                        replyView.undoManager?.removeAllActions(withTarget: replyView.textStorage as Any)
                    }
                    if handedOn { forwarding = false }
                }
                update()
            } catch {
                update()
                showError(error)
            }
        }
    }

    private func sendDraft() {
        saveComposing()
        guard canSendDraft else {
            NSSound.beep()
            return
        }
        Task {
            do {
                try await store.sendDraft()
            } catch {
                showError(error)
            }
            update()
        }
    }

    func closeThread() {
        // A thread just selected from the list's menu: its own text is the note.
        update()
        guard let id = store.selectedThreadID, canCloseThread else {
            NSSound.beep()
            return
        }
        let note = replyView.string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !note.isEmpty, let window = view.window else {
            performClose(id, note: "")
            return
        }
        let alert = NSAlert()
        alert.messageText = "Close the thread with your reply as the closing note?"
        alert.informativeText = "The text in the reply box is sent with the close. Without it, the thread is closed with “Closed.” and your text stays here."
        alert.addButton(withTitle: "Close with Note")
        alert.addButton(withTitle: "Close Without Note")
        alert.addButton(withTitle: "Cancel")
        alert.beginSheetModal(for: window) { [weak self] response in
            switch response {
            case .alertFirstButtonReturn: self?.performClose(id, note: note)
            case .alertSecondButtonReturn: self?.performClose(id, note: "")
            default: break
            }
        }
    }

    private func performClose(_ id: String, note: String) {
        Task {
            do {
                try await store.close(threadID: id, note: note)
                if !note.isEmpty, composing == .thread(id) { replyView.string = "" }
                update()
            } catch {
                update()
                showError(error)
            }
        }
    }

    private func showError(_ error: any Error) {
        if let window = view.window {
            window.presentError(error, modalFor: window, delegate: nil, didPresent: nil, contextInfo: nil)
        } else {
            NSApp.presentError(error)
        }
    }

    // MARK: NSSplitViewDelegate

    func splitView(_ splitView: NSSplitView, constrainMinCoordinate proposed: CGFloat, ofSubviewAt index: Int) -> CGFloat {
        max(proposed, 120)
    }

    func splitView(_ splitView: NSSplitView, constrainMaxCoordinate proposed: CGFloat, ofSubviewAt index: Int) -> CGFloat {
        min(proposed, splitView.bounds.height - Self.minimumReplyHeight)
    }
}
