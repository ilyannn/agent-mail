// Markdown to HTML for message bodies, which agents write: parsed with
// swift-markdown (cmark-gfm), printed by our own walker because the package's
// HTMLFormatter escapes nothing (text, code, link targets and raw HTML all go
// through verbatim). Here every piece of text is escaped, raw HTML is shown
// as text, and only http(s) and mailto links survive. `files/x` points into
// the thread's attachment directory through the agent-mail-file scheme
// (ThreadLinks.swift), and `![](files/x.png)` is shown inline; other images
// stay links (the page loads nothing from the network), and local paths stay
// text with the path as a tooltip. Smart punctuation is off: swift-markdown
// turns `"` and `--` into curly quotes and dashes by default, which misquotes
// commands. A long body can also be rendered folded: the first and last few
// lines with the middle in a <details> (see renderFolded).
import Foundation
import Markdown

public enum MarkdownHTML {
    static let parseOptions: ParseOptions = [.disableSmartOpts]

    /// A rendered body, and the attachments it shows inline as images (the
    /// thread view leaves those out of the thumbnails).
    public struct Rendered: Sendable, Equatable {
        public var html: String
        public var inlineImages: Set<String>
    }

    /// A long body cut in three: `head` and `tail` stay visible, `hidden`
    /// goes into the fold, which says how many lines (`hiddenLines`, as
    /// wrapped at 80 characters) it holds.
    public struct Folded: Sendable, Equatable {
        public var head: String
        public var hidden: String
        public var tail: String
        public var hiddenLines: Int
        public var inlineImages: Set<String>
    }

    /// The body as an HTML fragment. `thread` resolves `files/…` links
    /// (attachments); without it they are dropped.
    public static func render(_ markdown: String, thread: String? = nil) -> String {
        renderBody(markdown, thread: thread).html
    }

    public static func renderBody(_ markdown: String, thread: String?) -> Rendered {
        let document = Document(parsing: markdown, options: parseOptions)
        var walker = SafeHTMLWalker(thread: thread)
        walker.visit(document)
        return Rendered(html: walker.result, inlineImages: walker.inlineImages)
    }

    /// The body folded, or nil when folding would hide fewer than
    /// `minimumHidden` lines. It is cut between top-level blocks, between
    /// the items of a list and between the lines of a code block, never
    /// inside a paragraph, table or quote; the head always keeps the first
    /// block.
    /// - Parameters:
    ///   - headLines, tailLines: about how many lines stay visible above and
    ///     below the fold.
    public static func renderFolded(_ markdown: String, thread: String?, headLines: Int = 12, tailLines: Int = 6,
                                    minimumHidden: Int = 8) -> Folded? {
        let source = markdown.replacingOccurrences(of: "\r\n", with: "\n")
        let document = Document(parsing: source, options: parseOptions)
        let lines = source.split(separator: "\n", omittingEmptySubsequences: false)
        var images = Set<String>()
        let units = FoldUnit.units(of: document, lines: lines, thread: thread, images: &images)

        var headEnd = 0
        var headWeight = 0
        while headEnd < units.count, headEnd == 0 || headWeight + units[headEnd].weight <= headLines {
            headWeight += units[headEnd].weight
            headEnd += 1
        }
        var tailStart = units.count
        var tailWeight = 0
        while tailStart > headEnd, tailWeight + units[tailStart - 1].weight <= tailLines {
            tailWeight += units[tailStart - 1].weight
            tailStart -= 1
        }
        let hiddenUnits = units[headEnd..<tailStart]
        let hiddenLines = hiddenUnits.reduce(0) { $0 + $1.weight }
        guard hiddenLines >= minimumHidden else { return nil }
        return Folded(head: FoldUnit.join(units[..<headEnd]), hidden: FoldUnit.join(hiddenUnits),
                      tail: FoldUnit.join(units[tailStart...]), hiddenLines: hiddenLines, inlineImages: images)
    }

    /// Plain text for a notification body: the words without markup, on one
    /// line, cut at `limit` characters with an ellipsis.
    public static func plainPreview(_ markdown: String, limit: Int = 200) -> String {
        var walker = PlainTextWalker()
        walker.visit(Document(parsing: markdown, options: parseOptions))
        let flat = walker.result.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard flat.count > limit else { return flat }
        return String(flat.prefix(limit - 1)).trimmingCharacters(in: .whitespaces) + "…"
    }

    public static func escape(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.utf8.count)
        for ch in s.unicodeScalars {
            switch ch {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&#39;"
            default: out.unicodeScalars.append(ch)
            }
        }
        return out
    }

    /// The attachment name a `files/<name>` reference names, or nil.
    public static func attachmentName(_ reference: String) -> String? {
        let d = reference.trimmingCharacters(in: .whitespaces)
        guard d.hasPrefix("files/") else { return nil }
        let raw = String(d.dropFirst("files/".count))
        let name = raw.removingPercentEncoding ?? raw
        return AttachmentScheme.isPlainComponent(name) ? name : nil
    }

    /// The URL a link may point to, or nil to drop the link: http(s) and
    /// mailto as written, `files/<name>` through the attachment scheme.
    public static func safeDestination(_ destination: String, thread: String?) -> String? {
        let d = destination.trimmingCharacters(in: .whitespaces)
        if d.hasPrefix("files/") {
            guard let thread, let name = attachmentName(d) else { return nil }
            return AttachmentScheme.url(thread: thread, name: name)?.absoluteString
        }
        guard let url = URL(string: d), let scheme = url.scheme?.lowercased(),
              ["http", "https", "mailto"].contains(scheme) else { return nil }
        return url.absoluteString
    }

    /// A path on this Mac (`/x`, `~/x`, `file:`): not a link, shown as text.
    static func isLocalPath(_ destination: String) -> Bool {
        let d = destination.trimmingCharacters(in: .whitespaces)
        return d.hasPrefix("/") || d.hasPrefix("~/") || d.lowercased().hasPrefix("file:")
    }
}

/// A piece of a body that folding can't cut, with its height in lines.
/// Consecutive units of one list or code block share `group` and are
/// wrapped together, opening with the first one's `open`.
private struct FoldUnit {
    var html: String
    var weight: Int
    var group: Int?
    var open = ""
    var close = ""

    static func units(of document: Document, lines: [Substring], thread: String?,
                      images: inout Set<String>) -> [FoldUnit] {
        func weight(_ m: any Markup) -> Int {
            guard let r = m.range, !lines.isEmpty else { return 1 }
            let first = min(max(r.lowerBound.line, 1), lines.count)
            let last = min(max(r.upperBound.line, first), lines.count)
            return (first...last).reduce(0) { $0 + max(1, (lines[$1 - 1].count + 79) / 80) }
        }
        func html(_ m: any Markup) -> String {
            var walker = SafeHTMLWalker(thread: thread)
            walker.visit(m)
            images.formUnion(walker.inlineImages)
            return walker.result
        }
        var units = [FoldUnit]()
        for (group, block) in document.children.enumerated() {
            switch block {
            case let list as UnorderedList where list.childCount > 1:
                for item in list.children {
                    units.append(FoldUnit(html: html(item), weight: weight(item), group: group,
                                          open: "<ul>\n", close: "</ul>\n"))
                }
            case let list as OrderedList where list.childCount > 1:
                for (k, item) in list.children.enumerated() {
                    let n = Int(list.startIndex) + k
                    units.append(FoldUnit(html: html(item), weight: weight(item), group: group,
                                          open: n == 1 ? "<ol>\n" : "<ol start=\"\(n)\">\n", close: "</ol>\n"))
                }
            case let code as CodeBlock:
                let open = "<pre><code\(SafeHTMLWalker.languageClass(code))>"
                var codeLines = code.code.split(separator: "\n", omittingEmptySubsequences: false)
                if codeLines.last == "" { codeLines.removeLast() }
                if codeLines.isEmpty { codeLines = [""] }
                for line in codeLines {
                    units.append(FoldUnit(html: MarkdownHTML.escape(String(line)) + "\n", weight: 1, group: group,
                                          open: open, close: "</code></pre>\n"))
                }
            default:
                units.append(FoldUnit(html: html(block), weight: weight(block)))
            }
        }
        return units
    }

    static func join(_ units: ArraySlice<FoldUnit>) -> String {
        var out = ""
        var openGroup: Int?
        var close = ""
        for u in units {
            if u.group != openGroup {
                out += close
                close = ""
                openGroup = u.group
                if u.group != nil {
                    out += u.open
                    close = u.close
                }
            }
            out += u.html
        }
        return out + close
    }
}

private struct SafeHTMLWalker: MarkupWalker {
    let thread: String?
    var result = ""
    /// Attachments shown inline with `![](files/x.png)`.
    var inlineImages = Set<String>()
    private var alignments: [Table.ColumnAlignment?] = []
    private var column = 0
    private var inHead = false

    init(thread: String?) { self.thread = thread }

    private func esc(_ s: String) -> String { MarkdownHTML.escape(s) }

    static func languageClass(_ c: CodeBlock) -> String {
        let lang = c.language.map { $0.filter { $0.isLetter || $0.isNumber || "+-_.#".contains($0) } } ?? ""
        return lang.isEmpty ? "" : " class=\"language-\(MarkdownHTML.escape(lang))\""
    }

    // Blocks

    mutating func visitParagraph(_ p: Paragraph) {
        result += "<p>"
        descendInto(p)
        result += "</p>\n"
    }

    mutating func visitHeading(_ h: Heading) {
        let level = min(max(h.level, 1), 6)
        result += "<h\(level)>"
        descendInto(h)
        result += "</h\(level)>\n"
    }

    mutating func visitBlockQuote(_ q: BlockQuote) {
        result += "<blockquote>\n"
        descendInto(q)
        result += "</blockquote>\n"
    }

    mutating func visitCodeBlock(_ c: CodeBlock) {
        result += "<pre><code\(Self.languageClass(c))>\(esc(c.code))</code></pre>\n"
    }

    mutating func visitHTMLBlock(_ html: HTMLBlock) {
        result += "<pre class=\"raw-html\">\(esc(html.rawHTML))</pre>\n"
    }

    mutating func visitThematicBreak(_ t: ThematicBreak) { result += "<hr>\n" }

    mutating func visitOrderedList(_ l: OrderedList) {
        result += l.startIndex == 1 ? "<ol>\n" : "<ol start=\"\(l.startIndex)\">\n"
        descendInto(l)
        result += "</ol>\n"
    }

    mutating func visitUnorderedList(_ l: UnorderedList) {
        result += "<ul>\n"
        descendInto(l)
        result += "</ul>\n"
    }

    mutating func visitListItem(_ item: ListItem) {
        switch item.checkbox {
        case .checked?: result += "<li class=\"task done\"><span class=\"box\" aria-label=\"done\">☑</span> "
        case .unchecked?: result += "<li class=\"task\"><span class=\"box\" aria-label=\"not done\">☐</span> "
        case nil: result += "<li>"
        }
        descendInto(item)
        result += "</li>\n"
    }

    mutating func visitTable(_ t: Table) {
        alignments = t.columnAlignments
        result += "<table>\n"
        descendInto(t)
        result += "</table>\n"
        alignments = []
    }

    mutating func visitTableHead(_ head: Table.Head) {
        result += "<thead>\n<tr>\n"
        inHead = true
        column = 0
        descendInto(head)
        inHead = false
        result += "</tr>\n</thead>\n"
    }

    mutating func visitTableBody(_ body: Table.Body) {
        guard !body.isEmpty else { return }
        result += "<tbody>\n"
        descendInto(body)
        result += "</tbody>\n"
    }

    mutating func visitTableRow(_ row: Table.Row) {
        result += "<tr>\n"
        column = 0
        descendInto(row)
        result += "</tr>\n"
    }

    mutating func visitTableCell(_ cell: Table.Cell) {
        guard cell.colspan > 0, cell.rowspan > 0 else { return }
        let tag = inHead ? "th" : "td"
        var attrs = ""
        if column < alignments.count, let a = alignments[column] {
            switch a {
            case .left: attrs += " class=\"left\""
            case .center: attrs += " class=\"center\""
            case .right: attrs += " class=\"right\""
            }
        }
        if cell.colspan > 1 { attrs += " colspan=\"\(cell.colspan)\"" }
        if cell.rowspan > 1 { attrs += " rowspan=\"\(cell.rowspan)\"" }
        column += Int(cell.colspan)
        result += "<\(tag)\(attrs)>"
        descendInto(cell)
        result += "</\(tag)>\n"
    }

    // Inlines

    mutating func visitText(_ t: Text) { result += esc(t.string) }
    mutating func visitSoftBreak(_ b: SoftBreak) { result += "\n" }
    mutating func visitLineBreak(_ b: LineBreak) { result += "<br>\n" }
    mutating func visitInlineCode(_ c: InlineCode) { result += "<code>\(esc(c.code))</code>" }
    mutating func visitInlineHTML(_ h: InlineHTML) { result += esc(h.rawHTML) }

    mutating func visitEmphasis(_ e: Emphasis) { wrap("em", e) }
    mutating func visitStrong(_ s: Strong) { wrap("strong", s) }
    mutating func visitStrikethrough(_ s: Strikethrough) { wrap("del", s) }

    mutating func visitLink(_ link: Link) {
        let raw = link.destination ?? ""
        guard let dest = MarkdownHTML.safeDestination(raw, thread: thread) else {
            if MarkdownHTML.isLocalPath(raw) {
                result += "<span class=\"local-path\" title=\"\(esc(raw))\">"
                descendInto(link)
                result += "</span>"
            } else {
                descendInto(link)
            }
            return
        }
        result += "<a href=\"\(esc(dest))\">"
        descendInto(link)
        result += "</a>"
    }

    mutating func visitImage(_ image: Image) {
        let source = image.source ?? ""
        let alt = image.plainText.isEmpty ? (source.isEmpty ? "image" : source) : image.plainText
        if let name = MarkdownHTML.attachmentName(source), AttachmentScheme.isImage(name),
           let thread, let url = AttachmentScheme.url(thread: thread, name: name)?.absoluteString {
            inlineImages.insert(name)
            result += "<a class=\"inline-image\" href=\"\(esc(url))\" title=\"\(esc(name))\">"
                + "<img src=\"\(esc(url))\" alt=\"\(esc(alt))\"></a>"
            return
        }
        guard let dest = MarkdownHTML.safeDestination(source, thread: thread) else {
            result += "<span class=\"image\">[\(esc(alt))]</span>"
            return
        }
        result += "<a class=\"image\" href=\"\(esc(dest))\">[\(esc(alt))]</a>"
    }

    mutating func visitSymbolLink(_ s: SymbolLink) { result += "<code>\(esc(s.destination ?? ""))</code>" }

    private mutating func wrap(_ tag: String, _ markup: any Markup) {
        result += "<\(tag)>"
        descendInto(markup)
        result += "</\(tag)>"
    }
}

private struct PlainTextWalker: MarkupWalker {
    var result = ""

    mutating func visitText(_ t: Text) { result += t.string }
    mutating func visitInlineCode(_ c: InlineCode) { result += c.code }
    mutating func visitCodeBlock(_ c: CodeBlock) { result += " " + c.code + " " }
    mutating func visitInlineHTML(_ h: InlineHTML) { result += h.rawHTML }
    mutating func visitHTMLBlock(_ h: HTMLBlock) { result += " " + h.rawHTML + " " }
    mutating func visitSoftBreak(_ b: SoftBreak) { result += " " }
    mutating func visitLineBreak(_ b: LineBreak) { result += " " }
    mutating func visitImage(_ i: Image) { result += i.plainText }

    mutating func visitParagraph(_ p: Paragraph) { block(p) }
    mutating func visitHeading(_ h: Heading) { block(h) }
    mutating func visitListItem(_ i: ListItem) { block(i) }
    mutating func visitTableCell(_ c: Table.Cell) { block(c) }

    private mutating func block(_ m: any Markup) {
        descendInto(m)
        result += " "
    }
}
