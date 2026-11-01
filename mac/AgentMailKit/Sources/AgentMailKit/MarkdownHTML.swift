// Markdown to HTML for message bodies, which agents write: parsed with
// swift-markdown (cmark-gfm), printed by our own walker because the package's
// HTMLFormatter escapes nothing (text, code, link targets and raw HTML all go
// through verbatim). Here every piece of text is escaped, raw HTML is shown
// as text, images become links (the page's CSP loads no images), and only
// http(s), mailto and file links survive; `files/x` points into the thread's
// attachment directory. Smart punctuation is off: swift-markdown turns `"`
// and `--` into curly quotes and dashes by default, which misquotes commands.
import Foundation
import Markdown

public enum MarkdownHTML {
    static let parseOptions: ParseOptions = [.disableSmartOpts]

    /// The body as an HTML fragment. `filesDirectory` resolves `files/…`
    /// links (attachments); without it they are dropped.
    public static func render(_ markdown: String, filesDirectory: URL? = nil) -> String {
        let document = Document(parsing: markdown, options: parseOptions)
        var walker = SafeHTMLWalker(filesDirectory: filesDirectory)
        walker.visit(document)
        return walker.result
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

    /// The URL a link may point to, or nil to drop the link.
    public static func safeDestination(_ destination: String, filesDirectory: URL?) -> String? {
        let d = destination.trimmingCharacters(in: .whitespaces)
        if d.hasPrefix("files/") {
            guard let filesDirectory else { return nil }
            let name = String(d.dropFirst("files/".count)).removingPercentEncoding ?? String(d.dropFirst(6))
            guard !name.split(separator: "/").contains("..") else { return nil }
            return filesDirectory.appending(path: name).absoluteString
        }
        if d.hasPrefix("/") { return URL(filePath: d).absoluteString }
        guard let url = URL(string: d), let scheme = url.scheme?.lowercased(),
              ["http", "https", "mailto", "file"].contains(scheme) else { return nil }
        return url.absoluteString
    }
}

private struct SafeHTMLWalker: MarkupWalker {
    let filesDirectory: URL?
    var result = ""
    private var alignments: [Table.ColumnAlignment?] = []
    private var column = 0
    private var inHead = false

    init(filesDirectory: URL?) { self.filesDirectory = filesDirectory }

    private func esc(_ s: String) -> String { MarkdownHTML.escape(s) }

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
        let lang = c.language.map { $0.filter { $0.isLetter || $0.isNumber || "+-_.#".contains($0) } } ?? ""
        let cls = lang.isEmpty ? "" : " class=\"language-\(esc(lang))\""
        result += "<pre><code\(cls)>\(esc(c.code))</code></pre>\n"
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
        guard let dest = link.destination.flatMap({ MarkdownHTML.safeDestination($0, filesDirectory: filesDirectory) })
        else {
            descendInto(link)
            return
        }
        result += "<a href=\"\(esc(dest))\">"
        descendInto(link)
        result += "</a>"
    }

    mutating func visitImage(_ image: Image) {
        let alt = image.plainText.isEmpty ? (image.source ?? "image") : image.plainText
        guard let dest = image.source.flatMap({ MarkdownHTML.safeDestination($0, filesDirectory: filesDirectory) })
        else {
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
