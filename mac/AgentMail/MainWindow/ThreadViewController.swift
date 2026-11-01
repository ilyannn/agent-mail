// The thread view: the whole thread rendered as one HTML document in a
// WKWebView (content JavaScript off, strict CSP, attachments served by a
// scheme handler, every click decided by LinkPolicy), the newest message
// scrolled into view, and an inline reply box below it with the
// recipient, Close Thread and Send (⌘↩ or ⇧⌘D). The text size (View menu,
// pinch) is a page zoom kept in AppSettings.
import AgentMailKit
import AppKit
import Observation
import WebKit

final class ThreadViewController: NSViewController, WKNavigationDelegate, NSSplitViewDelegate, NSTextViewDelegate {
    private let store: MailStore
    private var webView: ThreadWebView!
    private let schemeHandler = AttachmentSchemeHandler()
    private let split = NSSplitView()
    private let replyPane = NSView()
    private let recipientPopup = NSPopUpButton()
    private let sendButton = NSButton(title: "Send", target: nil, action: nil)
    private let closeButton = NSButton(title: "Close Thread", target: nil, action: nil)
    private let statusLabel = NSTextField.label(font: .systemFont(ofSize: NSFont.smallSystemFontSize), color: .secondaryLabelColor)
    private let sendHint = NSTextField.label("⌘↩ to send", font: .systemFont(ofSize: NSFont.smallSystemFontSize),
                                             color: .tertiaryLabelColor)
    private var replyScroll: NSScrollView!
    private(set) var replyView: NSTextView!
    private let placeholder = NSTextField.label(font: .systemFont(ofSize: NSFont.systemFontSize + 4), color: .tertiaryLabelColor)
    private var observation: Task<Void, Never>?

    /// The thread id the reply box currently belongs to.
    private var draftThreadID: String?
    /// Thread id and fingerprint of what the web view shows.
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
        buildReplyPane()

        split.isVertical = false
        split.dividerStyle = .thin
        split.delegate = self
        split.addArrangedSubview(webView)
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
            webView.heightAnchor.constraint(greaterThanOrEqualToConstant: 120),
        ])
        view = container
    }

    private static let minimumReplyHeight: CGFloat = 130

    /// To and status on top, the text, then the hint, Close Thread and Send.
    private func buildReplyPane() {
        let toLabel = NSTextField.label("To:", color: .secondaryLabelColor)
        recipientPopup.translatesAutoresizingMaskIntoConstraints = false
        recipientPopup.controlSize = .small
        recipientPopup.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        recipientPopup.setAccessibilityLabel("Recipient")
        recipientPopup.target = self
        recipientPopup.action = #selector(recipientChanged(_:))
        let top = NSStackView(views: [toLabel, recipientPopup, statusLabel, NSView()])
        top.orientation = .horizontal
        top.spacing = 8
        top.edgeInsets = NSEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        statusLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        closeButton.target = self
        closeButton.action = #selector(closeThreadClicked(_:))
        closeButton.bezelStyle = .push
        closeButton.toolTip = "Close the thread (⌃⌘A). Text in the reply box becomes the closing note."
        closeButton.image = NSImage(systemSymbolName: "checkmark.circle", accessibilityDescription: nil)
        closeButton.imagePosition = .imageLeading
        sendButton.target = self
        sendButton.action = #selector(sendClicked(_:))
        sendButton.bezelStyle = .push
        sendButton.bezelColor = .controlAccentColor
        sendButton.keyEquivalent = ""
        sendButton.toolTip = "Send the reply (⌘↩ or ⇧⌘D)"
        sendButton.image = NSImage(systemSymbolName: "paperplane.fill", accessibilityDescription: nil)
        sendButton.imagePosition = .imageLeading
        sendHint.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let bottom = NSStackView(views: [sendHint, NSView(), closeButton, sendButton])
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
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        update()
        let changes = Observations { [store] in
            (store.selectedThreadID, store.detail, store.detailError, store.busyThreadIDs, store.index)
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

    private func update() {
        let id = store.selectedThreadID
        if id != draftThreadID { switchDraft(to: id) }
        let hasThread = id != nil
        split.isHidden = !hasThread
        placeholder.isHidden = hasThread
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
            let fp = detail.contentFingerprint
            if rendered?.id != detail.id || rendered?.fingerprint != fp {
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
        } else {
            statusLabel.stringValue = ""
        }

        updateRecipients(summary: summary, detail: detail)
        let busy = id.map { store.busyThreadIDs.contains($0) } ?? false
        let open = detail?.isOpen ?? summary?.isOpen ?? false
        closeButton.isEnabled = open && !busy
        sendButton.isEnabled = !busy && canSend
        replyView.isEditable = !busy
        if busy { statusLabel.stringValue = "Sending…" }
    }

    private var me: String { store.index.me }

    private func updateRecipients(summary: ThreadSummary?, detail: ThreadDetail?) {
        let participants = (detail?.participants ?? summary?.participants ?? []).filter { $0 != me }
        let preferred = detail?.replyRecipient(me: me) ?? summary?.replyRecipient(me: me)
        let current = recipientPopup.titleOfSelectedItem
        let names = participants.isEmpty ? [preferred].compactMap { $0 } : participants
        if recipientPopup.itemTitles != names {
            recipientPopup.removeAllItems()
            recipientPopup.addItems(withTitles: names)
            if let current, names.contains(current), draftThreadID == store.selectedThreadID, recipientWasChosen {
                recipientPopup.selectItem(withTitle: current)
            } else if let preferred {
                recipientPopup.selectItem(withTitle: preferred)
            }
        } else if !recipientWasChosen, let preferred, current != preferred {
            recipientPopup.selectItem(withTitle: preferred)
        }
    }

    /// The user picked a recipient for this draft; refreshes keep it.
    private var recipientWasChosen = false

    private func switchDraft(to id: String?) {
        if let old = draftThreadID {
            let text = replyView.string
            store.drafts[old] = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
        }
        draftThreadID = id
        recipientWasChosen = false
        replyView.string = id.flatMap { store.drafts[$0] } ?? ""
        replyView.undoManager?.removeAllActions(withTarget: replyView.textStorage as Any)
    }

    /// Puts text into the reply box of `id` (a failed notification reply).
    func restoreDraft(_ text: String, for id: String) {
        store.drafts[id] = text
        if draftThreadID == id {
            replyView.string = text
            view.window?.makeFirstResponder(replyView)
        }
    }

    func saveDraft() {
        if let id = draftThreadID {
            let text = replyView.string
            store.drafts[id] = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
        }
    }

    #if DEBUG
    func debugState() -> [String] {
        [
            "thread.placeholder: \(placeholder.isHidden ? "hidden" : placeholder.stringValue)",
            "thread.rendered: \(rendered.map { "\($0.id) \($0.fingerprint)" } ?? "-")",
            "thread.recipients: \(recipientPopup.itemTitles) selected=\(recipientPopup.titleOfSelectedItem ?? "-")",
            "thread.status: \(statusLabel.stringValue)",
            "thread.send: \(sendButton.isEnabled) close: \(closeButton.isEnabled)",
            "thread.reply: \(replyView.string.debugDescription) smartQuotes=\(replyView.isAutomaticQuoteSubstitutionEnabled) "
                + "spelling=\(replyView.isContinuousSpellCheckingEnabled) undo=\(replyView.allowsUndo)",
            "thread.zoom: page=\(webView.pageZoom) setting=\(store.settings.textZoom)",
            "thread.expanded: \(expandedFiles[rendered?.id ?? ""].map { $0.sorted() } ?? [])",
            "thread.schemeLog: \(schemeHandler.log.suffix(12))",
            "thread.opened: \(debugOpened)",
        ]
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

    private func render(_ detail: ThreadDetail) {
        let unread = detail.unreadFiles(me: me, count: store.index.thread(detail.id)?.unread ?? 0)
        let expanded = expandedFiles[detail.id, default: []].union(unread)
        expandedFiles[detail.id] = expanded
        let files = store.filesDirectory(detail.id)
        schemeHandler.thread = (detail.id, files)
        let html = ThreadDocument.html(thread: detail, me: me, css: css + Self.systemColorsCSS(), expanded: expanded,
                                       readAttachment: { Self.readText(named: $0, in: files) }) { [dateStyle] in
            $0.formatted(dateStyle)
        }
        rendered = (detail.id, detail.contentFingerprint)
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
        guard rendered != nil else { return }
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
        switch link {
        case .loadDocument: return .allow
        case let .openExternally(url): _ = openURL(url)
        case let .openAttachment(name): openAttachment(name, reveal: false)
        case let .revealAttachment(name): openAttachment(name, reveal: true)
        case let .openMessage(file): openMessage(file)
        case let .reply(file): reply(toMessage: file)
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
    }

    func makeTextBigger() { setTextZoom(TextZoom.bigger(than: textZoom)) }
    func makeTextSmaller() { setTextZoom(TextZoom.smaller(than: textZoom)) }
    func makeTextActualSize() { setTextZoom(TextZoom.actualSize) }

    /// Live while pinching, saved when the fingers lift.
    private func pinch(_ step: Double, ended: Bool) {
        let z = TextZoom.clamp((pinchZoom ?? webView.pageZoom) * (1 + step))
        pinchZoom = z
        webView.pageZoom = z
        if ended {
            pinchZoom = nil
            setTextZoom(z)
        }
    }

    // MARK: Actions

    private var canSend: Bool {
        store.selectedThreadID != nil && recipientPopup.titleOfSelectedItem != nil
            && !replyView.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var canSendReply: Bool {
        guard let id = store.selectedThreadID else { return false }
        return canSend && !store.busyThreadIDs.contains(id)
    }

    var canCloseThread: Bool {
        guard let id = store.selectedThreadID, !store.busyThreadIDs.contains(id) else { return false }
        return (store.detail?.id == id ? store.detail?.isOpen : store.index.thread(id)?.isOpen) ?? false
    }

    func textDidChange(_ notification: Notification) {
        sendButton.isEnabled = canSendReply
    }

    @objc private func recipientChanged(_ sender: Any?) {
        recipientWasChosen = true
    }

    /// ⌘R: focus the reply box, quoting what is selected in the thread
    /// (or, from a card's Reply button, what was selected before the click).
    func focusReply(fallbackSelection: String = "") {
        guard store.selectedThreadID != nil else { return }
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
            if recipientPopup.item(withTitle: to) == nil { recipientPopup.addItem(withTitle: to) }
            recipientPopup.selectItem(withTitle: to)
            recipientWasChosen = true
        }
        focusReply(fallbackSelection: selection)
    }

    /// Space in the thread list: page down the thread.
    func pageDown() {
        webView.evaluateJavaScript("window.scrollBy(0, window.innerHeight * 0.9)", completionHandler: nil)
    }

    @objc private func sendClicked(_ sender: Any?) { sendReply() }
    @objc private func closeThreadClicked(_ sender: Any?) { closeThread() }

    func sendReply() {
        guard let id = store.selectedThreadID, canSendReply, let to = recipientPopup.titleOfSelectedItem else {
            NSSound.beep()
            return
        }
        let body = replyView.string
        Task {
            do {
                try await store.reply(threadID: id, to: to, body: body)
                if draftThreadID == id, replyView.string == body {
                    replyView.string = ""
                    replyView.undoManager?.removeAllActions(withTarget: replyView.textStorage as Any)
                }
                update()
            } catch {
                update()
                showError(error)
            }
        }
    }

    func closeThread() {
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
                if !note.isEmpty, draftThreadID == id { replyView.string = "" }
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
