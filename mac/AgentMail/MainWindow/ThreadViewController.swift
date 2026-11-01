// The thread view: the whole thread rendered as one HTML document in a
// WKWebView (content JavaScript off, strict CSP, links handed to the
// system), the newest message scrolled into view, and an inline reply box
// below it with the recipient, Close Thread and Send.
import AgentMailKit
import AppKit
import Observation
import WebKit

final class ThreadViewController: NSViewController, WKNavigationDelegate, NSSplitViewDelegate, NSTextViewDelegate {
    private let store: MailStore
    private var webView: WKWebView!
    private let split = NSSplitView()
    private let replyPane = NSView()
    private let recipientPopup = NSPopUpButton()
    private let sendButton = NSButton(title: "Send", target: nil, action: nil)
    private let closeButton = NSButton(title: "Close Thread", target: nil, action: nil)
    private let statusLabel = NSTextField.label(font: .systemFont(ofSize: NSFont.smallSystemFontSize), color: .secondaryLabelColor)
    private var replyScroll: NSScrollView!
    private(set) var replyView: NSTextView!
    private let placeholder = NSTextField.label(font: .systemFont(ofSize: NSFont.systemFontSize + 4), color: .tertiaryLabelColor)
    private var observation: Task<Void, Never>?

    /// The thread id the reply box currently belongs to.
    private var draftThreadID: String?
    /// Thread id and fingerprint of what the web view shows.
    private var rendered: (id: String, fingerprint: String)?
    private lazy var css: String = {
        guard let url = Bundle.main.url(forResource: "thread", withExtension: "css"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return s
    }()
    private let dateStyle = Date.FormatStyle(date: .abbreviated, time: .shortened)

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
        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = false
        webView.allowsMagnification = true
        webView.underPageBackgroundColor = .textBackgroundColor
        webView.setAccessibilityLabel("Thread")

        let (scroll, text) = PlainTextEditor.make(accessibilityLabel: "Reply")
        replyScroll = scroll
        replyView = text
        replyView.delegate = self
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
            replyPane.heightAnchor.constraint(greaterThanOrEqualToConstant: 90),
            webView.heightAnchor.constraint(greaterThanOrEqualToConstant: 120),
        ])
        view = container
    }

    private func buildReplyPane() {
        let toLabel = NSTextField.label("To:", color: .secondaryLabelColor)
        recipientPopup.translatesAutoresizingMaskIntoConstraints = false
        recipientPopup.controlSize = .small
        recipientPopup.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        recipientPopup.setAccessibilityLabel("Recipient")
        recipientPopup.target = self
        recipientPopup.action = #selector(recipientChanged(_:))
        closeButton.target = self
        closeButton.action = #selector(closeThreadClicked(_:))
        closeButton.controlSize = .small
        closeButton.toolTip = "Close the thread (⌃⌘A). Text in the reply box becomes the closing note."
        sendButton.target = self
        sendButton.action = #selector(sendClicked(_:))
        sendButton.controlSize = .small
        sendButton.keyEquivalent = ""
        sendButton.toolTip = "Send the reply (⇧⌘D)"
        if let image = NSImage(systemSymbolName: "paperplane", accessibilityDescription: nil) {
            sendButton.image = image
            sendButton.imagePosition = .imageLeading
        }
        let bar = NSStackView(views: [toLabel, recipientPopup, statusLabel, NSView(), closeButton, sendButton])
        bar.orientation = .horizontal
        bar.spacing = 8
        bar.edgeInsets = NSEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        bar.translatesAutoresizingMaskIntoConstraints = false
        bar.setHuggingPriority(.defaultHigh, for: .vertical)
        statusLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let separator = NSBox()
        separator.boxType = .separator
        separator.translatesAutoresizingMaskIntoConstraints = false
        replyScroll.translatesAutoresizingMaskIntoConstraints = false
        replyPane.addSubview(bar)
        replyPane.addSubview(separator)
        replyPane.addSubview(replyScroll)
        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: replyPane.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: replyPane.trailingAnchor),
            bar.topAnchor.constraint(equalTo: replyPane.topAnchor),
            separator.leadingAnchor.constraint(equalTo: replyPane.leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: replyPane.trailingAnchor),
            separator.topAnchor.constraint(equalTo: bar.bottomAnchor),
            replyScroll.leadingAnchor.constraint(equalTo: replyPane.leadingAnchor),
            replyScroll.trailingAnchor.constraint(equalTo: replyPane.trailingAnchor),
            replyScroll.topAnchor.constraint(equalTo: separator.bottomAnchor),
            replyScroll.bottomAnchor.constraint(equalTo: replyPane.bottomAnchor),
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
        if split.subviews.count == 2, replyPane.frame.height < 90 || replyPane.frame.height > split.bounds.height * 0.6 {
            split.setPosition(max(split.bounds.height - 170, 120), ofDividerAt: 0)
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
        ]
    }
    #endif

    // MARK: Rendering

    private func render(_ detail: ThreadDetail) {
        let html = ThreadDocument.html(thread: detail, me: me, css: css,
                                       filesDirectory: store.filesDirectory(detail.id)) { [dateStyle] in
            $0.formatted(dateStyle)
        }
        rendered = (detail.id, detail.contentFingerprint)
        webView.loadHTMLString(html, baseURL: nil)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard rendered != nil else { return }
        // App-injected script; runs even with content JavaScript disabled.
        webView.evaluateJavaScript("var n = document.getElementById('newest'); if (n) { n.scrollIntoView({block: 'start'}); } !!n") { result, error in
            if let error {
                Timing.note("scroll to newest failed: \(error.localizedDescription)")
            } else {
                Timing.note("scrolled to newest: \(String(describing: result))")
            }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        // The document itself loads; every link goes to the system.
        if action.navigationType == .other, action.request.url?.absoluteString == "about:blank" { return .allow }
        if action.navigationType == .linkActivated, let url = action.request.url {
            NSWorkspace.shared.open(url)
        }
        return .cancel
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

    /// ⌘R: focus the reply box, quoting what is selected in the thread.
    func focusReply() {
        guard store.selectedThreadID != nil else { return }
        view.window?.makeFirstResponder(replyView)
        webView.evaluateJavaScript("window.getSelection().toString()") { [weak self] result, _ in
            guard let self, let selection = result as? String else { return }
            let quoted = Quoting.replyPrefill(selection: selection)
            guard !quoted.isEmpty else { return }
            replyView.insertText(quoted, replacementRange: replyView.selectedRange())
        }
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
        min(proposed, splitView.bounds.height - 90)
    }
}
