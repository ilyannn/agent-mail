// The files waiting to go with what the reply box holds: a row of chips
// between the text and the buttons, each with the file's thumbnail or icon,
// its name (click to open it) and a button that takes it off. It scrolls
// sideways when they don't fit.
import AgentMailKit
import AppKit

final class AttachmentStrip: NSView {
    static let height: CGFloat = 34

    var onOpen: (URL) -> Void = { _ in }
    var onRemove: (URL) -> Void = { _ in }
    private(set) var files: [URL] = []

    private let scroll = NSScrollView()
    private let stack = NSStackView()

    override init(frame: NSRect) {
        super.init(frame: frame)
        stack.orientation = .horizontal
        stack.spacing = 6
        stack.edgeInsets = NSEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
        stack.translatesAutoresizingMaskIntoConstraints = false
        let document = FlippedView()
        document.translatesAutoresizingMaskIntoConstraints = false
        document.addSubview(stack)
        scroll.documentView = document
        scroll.drawsBackground = false
        scroll.hasHorizontalScroller = true
        scroll.autohidesScrollers = true
        scroll.horizontalScrollElasticity = .allowed
        scroll.verticalScrollElasticity = .none
        scroll.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: topAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            document.topAnchor.constraint(equalTo: scroll.contentView.topAnchor),
            document.bottomAnchor.constraint(equalTo: scroll.contentView.bottomAnchor),
            document.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor),
            stack.leadingAnchor.constraint(equalTo: document.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: document.trailingAnchor),
            stack.centerYAnchor.constraint(equalTo: document.centerYAnchor),
        ])
        setAccessibilityElement(true)
        setAccessibilityRole(.list)
        setAccessibilityLabel("Attachments")
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    /// Shows `files`; the same list again changes nothing.
    func show(_ files: [URL]) {
        guard files != self.files else { return }
        self.files = files
        for v in stack.arrangedSubviews {
            stack.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
        for file in files { stack.addArrangedSubview(chip(for: file)) }
    }

    private func chip(for file: URL) -> NSView {
        let name = file.lastPathComponent
        let chip = ChipView()
        chip.toolTip = Self.tip(for: file)

        let icon = NSImageView(image: Self.preview(of: file))
        icon.imageScaling = .scaleProportionallyUpOrDown
        let open = ClosureButton(title: name) { [weak self] in self?.onOpen(file) }
        open.isBordered = false
        open.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        open.lineBreakMode = .byTruncatingMiddle
        open.setAccessibilityLabel("Open \(name)")
        let remove = ClosureButton(title: "") { [weak self] in self?.onRemove(file) }
        remove.image = NSImage(systemSymbolName: "xmark.circle.fill", accessibilityDescription: "Remove \(name)")
        remove.isBordered = false
        remove.imagePosition = .imageOnly
        remove.contentTintColor = .secondaryLabelColor
        remove.toolTip = "Don’t send \(name)"
        remove.setAccessibilityLabel("Remove \(name)")

        let row = NSStackView(views: [icon, open, remove])
        row.orientation = .horizontal
        row.spacing = 4
        row.edgeInsets = NSEdgeInsets(top: 3, left: 5, bottom: 3, right: 4)
        row.translatesAutoresizingMaskIntoConstraints = false
        chip.addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: chip.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: chip.trailingAnchor),
            row.topAnchor.constraint(equalTo: chip.topAnchor),
            row.bottomAnchor.constraint(equalTo: chip.bottomAnchor),
            icon.widthAnchor.constraint(equalToConstant: 20),
            icon.heightAnchor.constraint(equalToConstant: 20),
            open.widthAnchor.constraint(lessThanOrEqualToConstant: 220),
        ])
        return chip
    }

    /// The image itself for a picture that isn't huge, else the file's icon.
    private static func preview(of file: URL) -> NSImage {
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? .max
        if AttachmentScheme.isImage(file.lastPathComponent), size <= 20_000_000,
           let image = NSImage(contentsOf: file) {
            return image
        }
        return NSWorkspace.shared.icon(forFile: file.path)
    }

    /// Where the file is, and its size.
    private static func tip(for file: URL) -> String {
        let path = (file.path as NSString).abbreviatingWithTildeInPath
        guard let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize else { return path }
        return path + "\n" + ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)
    }
}

/// A button that runs a closure.
private final class ClosureButton: NSButton {
    private var handler: () -> Void = {}

    convenience init(title: String, handler: @escaping () -> Void) {
        self.init(title: title, target: nil, action: nil)
        self.handler = handler
        target = self
        action = #selector(run)
    }

    @objc private func run() { handler() }
}

private final class FlippedView: NSView {
    override var isFlipped: Bool { true }
}

/// A chip's rounded fill, in the appearance's colour.
private final class ChipView: NSView {
    override init(frame: NSRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func draw(_ dirtyRect: NSRect) {
        NSColor.quaternaryLabelColor.setFill()
        NSBezierPath(roundedRect: bounds, xRadius: 6, yRadius: 6).fill()
    }
}
