// New Message (⌘N): a separate window, as in Mail, with To (a combo box of
// known agents), Subject (optional: the CLI uses the first line) and a
// plain-text body. ⇧⌘D sends; the window closes once the CLI succeeded.
import AgentMailKit
import AppKit

final class ComposeWindowController: NSWindowController, NSWindowDelegate, NSMenuItemValidation, NSTextViewDelegate {
    private let store: MailStore
    private let toField = NSComboBox()
    private let subjectField = NSTextField()
    private var bodyView: NSTextView!
    private let sendButton = NSButton(title: "Send", target: nil, action: nil)
    private var sending = false
    private var sent = false
    var onClose: () -> Void = {}

    init(store: MailStore, recipient: String?) {
        self.store = store
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 560, height: 420),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable],
                              backing: .buffered, defer: false)
        window.title = "New Message"
        window.minSize = NSSize(width: 380, height: 260)
        window.isReleasedWhenClosed = false
        window.tabbingMode = .disallowed
        window.autorecalculatesKeyViewLoop = true
        super.init(window: window)
        window.delegate = self
        build(recipient: recipient)
        window.center()
        window.setFrameAutosaveName("Compose")
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private func build(recipient: String?) {
        guard let window else { return }
        toField.addItems(withObjectValues: store.index.agents.map(\.name))
        toField.completes = true
        toField.stringValue = recipient ?? ""
        toField.placeholderString = "repo name"
        toField.setAccessibilityLabel("To")
        subjectField.placeholderString = "Optional: the first line of the message"
        subjectField.setAccessibilityLabel("Subject")
        let grid = NSGridView(views: [
            [NSTextField.label("To:", color: .secondaryLabelColor), toField],
            [NSTextField.label("Subject:", color: .secondaryLabelColor), subjectField],
        ])
        grid.column(at: 0).xPlacement = .trailing
        grid.rowAlignment = .firstBaseline
        grid.columnSpacing = 8
        grid.rowSpacing = 8
        grid.translatesAutoresizingMaskIntoConstraints = false

        let (scroll, text) = PlainTextEditor.make(accessibilityLabel: "Message")
        bodyView = text
        bodyView.delegate = self
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.borderType = .lineBorder

        sendButton.target = self
        sendButton.action = #selector(sendReply(_:))
        sendButton.bezelStyle = .push
        sendButton.toolTip = "Send (⇧⌘D)"
        sendButton.translatesAutoresizingMaskIntoConstraints = false
        let hint = NSTextField.label("Markdown. The recipient sees it in its next session, or now if it’s on duty.",
                                     font: .systemFont(ofSize: NSFont.smallSystemFontSize), color: .secondaryLabelColor)

        let content = NSView()
        for v in [grid, scroll, hint, sendButton] as [NSView] { content.addSubview(v) }
        NSLayoutConstraint.activate([
            grid.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 16),
            grid.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -16),
            grid.topAnchor.constraint(equalTo: content.topAnchor, constant: 16),
            scroll.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 16),
            scroll.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -16),
            scroll.topAnchor.constraint(equalTo: grid.bottomAnchor, constant: 12),
            hint.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 16),
            hint.centerYAnchor.constraint(equalTo: sendButton.centerYAnchor),
            hint.trailingAnchor.constraint(lessThanOrEqualTo: sendButton.leadingAnchor, constant: -8),
            sendButton.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -16),
            sendButton.topAnchor.constraint(equalTo: scroll.bottomAnchor, constant: 12),
            sendButton.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -16),
            toField.widthAnchor.constraint(greaterThanOrEqualToConstant: 200),
        ])
        window.contentView = content
        window.initialFirstResponder = recipient == nil ? toField : bodyView
    }

    private var body: String { bodyView.string }

    @objc func sendReply(_ sender: Any?) {
        let to = toField.stringValue.trimmingCharacters(in: .whitespaces)
        guard Formatting.isValidName(to) else {
            fail("Enter a recipient: a repo name without spaces or slashes.", focus: toField)
            return
        }
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            fail("Write a message first.", focus: bodyView)
            return
        }
        guard !sending else { return }
        sending = true
        sendButton.isEnabled = false
        let subject = subjectField.stringValue
        let text = body
        Task {
            defer {
                sending = false
                sendButton.isEnabled = true
            }
            do {
                // The new thread shows up through the store watcher.
                _ = try await store.send(to: to, subject: subject, body: text)
                sent = true
                close()
            } catch {
                if let window { window.presentError(error, modalFor: window, delegate: nil, didPresent: nil, contextInfo: nil) }
            }
        }
    }

    private func fail(_ message: String, focus: NSView) {
        guard let window else { return }
        let alert = NSAlert()
        alert.messageText = message
        alert.beginSheetModal(for: window) { _ in window.makeFirstResponder(focus) }
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        if menuItem.action == #selector(sendReply(_:)) { return !sending }
        return true
    }

    // MARK: NSWindowDelegate

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if sent || body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
        let alert = NSAlert()
        alert.messageText = "Discard this message?"
        alert.informativeText = "It hasn’t been sent."
        alert.addButton(withTitle: "Discard")
        alert.addButton(withTitle: "Cancel")
        alert.beginSheetModal(for: sender) { [weak self] response in
            if response == .alertFirstButtonReturn {
                self?.sent = true
                sender.close()
            }
        }
        return false
    }

    func windowWillClose(_ notification: Notification) {
        onClose()
    }
}
