// Small AppKit pieces shared by the windows: the plain-text editor used for
// replies and new messages, label shorthands, a view filled with the text
// background, and the dot image.
import AppKit

enum PlainTextEditor {
    /// An NSTextView for Markdown bodies: plain text, undo and spelling on,
    /// smart quotes and dashes off (curly quotes break code; Edit ›
    /// Substitutions still turns them on), and the find bar.
    static func make(accessibilityLabel: String) -> (NSScrollView, EditorTextView) {
        let scroll = EditorTextView.scrollableTextView()
        let tv = scroll.documentView as! EditorTextView
        tv.isRichText = false
        tv.importsGraphics = false
        tv.allowsUndo = true
        tv.isContinuousSpellCheckingEnabled = true
        tv.isAutomaticQuoteSubstitutionEnabled = false
        tv.isAutomaticDashSubstitutionEnabled = false
        tv.isAutomaticTextReplacementEnabled = false
        tv.isAutomaticSpellingCorrectionEnabled = false
        tv.smartInsertDeleteEnabled = false
        tv.usesFindBar = true
        tv.isIncrementalSearchingEnabled = true
        tv.font = .systemFont(ofSize: NSFont.systemFontSize)
        tv.textContainerInset = NSSize(width: 6, height: 8)
        tv.setAccessibilityLabel(accessibilityLabel)
        scroll.hasVerticalScroller = true
        scroll.borderType = .noBorder
        return (scroll, tv)
    }
}

extension NSTextField {
    static func label(_ text: String = "", font: NSFont = .systemFont(ofSize: NSFont.systemFontSize),
                      color: NSColor = .labelColor) -> NSTextField {
        let l = NSTextField(labelWithString: text)
        l.font = font
        l.textColor = color
        l.lineBreakMode = .byTruncatingTail
        l.translatesAutoresizingMaskIntoConstraints = false
        return l
    }

    static func wrapping(_ text: String, color: NSColor = .secondaryLabelColor) -> NSTextField {
        let l = NSTextField(wrappingLabelWithString: text)
        l.textColor = color
        l.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        l.translatesAutoresizingMaskIntoConstraints = false
        return l
    }
}

/// The editor's text view: ⌘↩ while it has focus runs `onCommandReturn`
/// (Send). It comes as a key equivalent, before the text system sees it.
/// With `onAttach` set, files pasted or dropped on it, and an image pasted
/// without text, go to `onAttach` instead of into the text.
final class EditorTextView: NSTextView {
    var onCommandReturn: (() -> Void)?
    var onAttach: ((PastedAttachment) -> Void)? {
        didSet { updateDragTypeRegistration() }
    }

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        if let onCommandReturn, event.isCommandReturn, window?.firstResponder === self {
            onCommandReturn()
            return true
        }
        return super.performKeyEquivalent(with: event)
    }

    /// Paste is enabled, and a drop accepted, for files and images too.
    override var readablePasteboardTypes: [NSPasteboard.PasteboardType] {
        let text = super.readablePasteboardTypes
        guard onAttach != nil else { return text }
        return text + PastedAttachment.pasteboardTypes.filter { !text.contains($0) }
    }

    override func paste(_ sender: Any?) {
        if attach(from: .general) { return }
        super.paste(sender)
    }

    override func dragOperation(for dragInfo: any NSDraggingInfo, type: NSPasteboard.PasteboardType) -> NSDragOperation {
        if isEditable, onAttach != nil, PastedAttachment.read(dragInfo.draggingPasteboard) != nil { return .copy }
        return super.dragOperation(for: dragInfo, type: type)
    }

    override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        if attach(from: sender.draggingPasteboard) { return true }
        return super.performDragOperation(sender)
    }

    /// What paste and drop do first: true when `pasteboard` held files or an
    /// image, now handed to `onAttach`.
    func attach(from pasteboard: NSPasteboard) -> Bool {
        guard isEditable, let onAttach, let found = PastedAttachment.read(pasteboard) else { return false }
        onAttach(found)
        return true
    }
}

extension NSEvent {
    /// ⌘↩ (Return or Enter with Command alone), which sends from the reply
    /// box, the new message's included.
    var isCommandReturn: Bool {
        type == .keyDown && (keyCode == 36 || keyCode == 76)
            && modifierFlags.intersection([.command, .shift, .option, .control]) == .command
    }
}

/// A plain view filled with the text background (light or dark, as the
/// appearance draws it), so native views can sit on a web page's Canvas.
final class FilledView: NSView {
    override var isOpaque: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        NSColor.textBackgroundColor.setFill()
        dirtyRect.fill()
    }
}

extension NSImage {
    /// A small filled dot in `color`, e.g. unread or on duty.
    static func dot(_ color: NSColor, filled: Bool = true, size: CGFloat = 8, label: String) -> NSImage? {
        NSImage(systemSymbolName: filled ? "circle.fill" : "circle", accessibilityDescription: label)?
            .withSymbolConfiguration(.init(pointSize: size, weight: .regular).applying(.init(paletteColors: [color])))
    }
}
