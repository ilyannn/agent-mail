// Small AppKit pieces shared by the windows: the plain-text editor used for
// replies and new messages, a count/state badge, and label shorthands.
import AppKit

enum PlainTextEditor {
    /// An NSTextView for Markdown bodies: plain text, undo and spelling on,
    /// smart quotes and dashes off (curly quotes break code; Edit ›
    /// Substitutions still turns them on), and the find bar.
    static func make(accessibilityLabel: String) -> (NSScrollView, NSTextView) {
        let scroll = NSTextView.scrollableTextView()
        let tv = scroll.documentView as! NSTextView
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

/// A rounded, tinted label: "Undelivered", "Your turn", "Closed".
final class BadgeView: NSView {
    private let label = NSTextField(labelWithString: "")
    var color: NSColor = .secondaryLabelColor {
        didSet {
            label.textColor = color
            needsDisplay = true
        }
    }
    var text: String = "" {
        didSet {
            label.stringValue = text
            isHidden = text.isEmpty
        }
    }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.cornerRadius = 4
        label.font = .systemFont(ofSize: NSFont.smallSystemFontSize - 1, weight: .semibold)
        label.translatesAutoresizingMaskIntoConstraints = false
        label.setContentCompressionResistancePriority(.required, for: .horizontal)
        addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 5),
            label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -5),
            label.topAnchor.constraint(equalTo: topAnchor, constant: 1),
            label.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -1),
        ])
        setContentCompressionResistancePriority(.required, for: .horizontal)
        setContentHuggingPriority(.required, for: .horizontal)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var wantsUpdateLayer: Bool { true }

    override func updateLayer() {
        // Resolved here so the tint follows light and dark appearance.
        layer?.backgroundColor = color.withAlphaComponent(0.16).cgColor
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

extension NSImage {
    /// A small filled dot in `color`, e.g. unread or on duty.
    static func dot(_ color: NSColor, filled: Bool = true, size: CGFloat = 8, label: String) -> NSImage? {
        NSImage(systemSymbolName: filled ? "circle.fill" : "circle", accessibilityDescription: label)?
            .withSymbolConfiguration(.init(pointSize: size, weight: .regular).applying(.init(paletteColors: [color])))
    }
}
