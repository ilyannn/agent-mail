// A whole thread as one HTML document for the web view: a strict CSP (no
// scripts, no network; images only from the attachment scheme), the app's
// stylesheet inlined, and every message as a card with its header (from →
// to, date, Open and Reply buttons), its body, and its attachments (links,
// image thumbnails, Markdown files rendered in a collapsible section). Long
// read messages start folded with the middle in a <details>, which works
// without JavaScript; unread ones and the newest never do. The newest is
// marked so the view can scroll to it.
import Foundation

public enum ThreadDocument {
    public static let contentSecurityPolicy =
        "default-src 'none'; style-src 'unsafe-inline'; img-src data: \(AttachmentScheme.scheme):"

    /// - Parameters:
    ///   - css: the stylesheet, inlined (a linked one would need a looser CSP).
    ///   - expanded: message files shown unfolded however long (the unread
    ///     ones); the newest always is.
    ///   - readAttachment: the text of a Markdown attachment by name, or nil
    ///     to only link it.
    ///   - formatDate: local time for a message date.
    public static func html(thread: ThreadDetail, me: String, css: String, expanded: Set<String> = [],
                            readAttachment: (String) -> String? = { _ in nil },
                            formatDate: (Date) -> String) -> String {
        let e = MarkdownHTML.escape
        var meta = [String]()
        if let n = thread.number { meta.append("#\(n)") }
        meta.append(thread.isOpen ? "open" : "closed")
        if thread.isOpen, let owner = thread.owner { meta.append("waiting on \(owner == me ? "you" : owner)") }
        if let from = thread.from { meta.append("started by \(from)") }
        let replyAll = LinkPolicy.actionURL(.reply, thread: thread.id).map {
            " <a class=\"button\" href=\"\(e($0.absoluteString))\" title=\"Reply (⌘R)\">Reply</a>"
        } ?? ""

        var body = """
            <header class="thread">
            <h1>\(e(thread.subject))</h1>
            <p class="meta"><span>\(meta.map(e).joined(separator: " · "))</span>\(replyAll)</p>
            </header>

            """
        for (i, m) in thread.msgs.enumerated() {
            let newest = i == thread.msgs.count - 1
            body += card(m, index: i, thread: thread.id, me: me, newest: newest,
                         unread: expanded.contains(m.file), readAttachment: readAttachment, formatDate: formatDate)
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="\(contentSecurityPolicy)">
            <meta name="color-scheme" content="light dark">
            <title>\(e(thread.subject))</title>
            <style>
            \(css)
            </style>
            </head>
            <body>
            <main>
            \(body)</main>
            </body>
            </html>

            """
    }

    static func card(_ m: Message, index i: Int, thread: String, me: String, newest: Bool, unread: Bool,
                     readAttachment: (String) -> String?, formatDate: (Date) -> String) -> String {
        let e = MarkdownHTML.escape
        let folded = MessageFolding.startsFolded(body: m.body, isUnread: unread, isNewest: newest)
            ? MarkdownHTML.renderFolded(m.body, thread: thread) : nil
        let rendered = folded == nil ? MarkdownHTML.renderBody(m.body, thread: thread) : nil
        let inlineImages = folded?.inlineImages ?? rendered?.inlineImages ?? []

        var classes = ["msg"]
        if m.from == me { classes.append("mine") }
        if unread { classes.append("unread") }
        if newest { classes.append("newest") }
        if folded != nil { classes.append("folded") }
        let id = newest ? "newest" : "m\(i + 1)"
        var out = "<article class=\"\(classes.joined(separator: " "))\" id=\"\(id)\">\n<header>"
        if unread { out += "<span class=\"dot\" role=\"img\" aria-label=\"unread\"></span>" }
        out += "<span class=\"who\"><span class=\"from\">\(e(m.from ?? "?"))</span>"
        out += " <span class=\"arrow\" aria-label=\"to\">→</span> "
        out += "<span class=\"to\">\(e(m.to ?? "?"))</span></span>"
        if let d = m.date {
            out += " <time datetime=\"\(e(d.formatted(.iso8601)))\">\(e(formatDate(d)))</time>"
        }
        if let s = m.status { out += " <span class=\"status \(e(s))\">\(s == "done" ? "closed" : e(s))</span>" }
        out += "<span class=\"actions\">"
        if let open = LinkPolicy.actionURL(.open, thread: thread, item: m.file) {
            out += "<a class=\"button\" href=\"\(e(open.absoluteString))\" title=\"Open the message file in your Markdown app\">Open</a>"
        }
        if let reply = LinkPolicy.actionURL(.reply, thread: thread, item: m.file) {
            let to = m.from == me ? m.to : m.from
            out += "<a class=\"button\" href=\"\(e(reply.absoluteString))\" title=\"Reply to \(e(to ?? "?"))\">Reply</a>"
        }
        out += "</span></header>\n"

        if let folded {
            out += "<div class=\"body\">\n\(folded.head)"
            out += "<details class=\"fold\"><summary><span class=\"more\">Show \(folded.hiddenLines) more lines</span>"
            out += "<span class=\"less\">Hide \(folded.hiddenLines) lines</span></summary>\n"
            out += "\(folded.hidden)</details>\n\(folded.tail)</div>\n"
        } else if let rendered {
            out += "<div class=\"body\">\n\(rendered.html)</div>\n"
        }
        out += attachments(m.attachments, thread: thread, inlineImages: inlineImages, open: unread || newest,
                           readAttachment: readAttachment)
        out += "</article>\n"
        return out
    }

    /// The attachment list, thumbnails of the images the body doesn't show
    /// inline, and Markdown attachments rendered in collapsible sections
    /// (open on an unread or the newest message).
    static func attachments(_ refs: [String], thread: String, inlineImages: Set<String>, open: Bool,
                            readAttachment: (String) -> String?) -> String {
        guard !refs.isEmpty else { return "" }
        let e = MarkdownHTML.escape
        var list = ""
        var thumbs = ""
        var docs = ""
        for ref in refs {
            guard let name = MarkdownHTML.attachmentName(ref), let url = AttachmentScheme.url(thread: thread, name: name)
            else {
                list += "<li>\(e(ref.hasPrefix("files/") ? String(ref.dropFirst(6)) : ref))</li>\n"
                continue
            }
            let href = e(url.absoluteString)
            list += "<li><a class=\"file\" href=\"\(href)\" title=\"Open \(e(name))\">\(e(name))</a>"
            if let reveal = LinkPolicy.actionURL(.reveal, thread: thread, item: name) {
                list += " <a class=\"reveal\" href=\"\(e(reveal.absoluteString))\" title=\"Show \(e(name)) in Finder\">Show in Finder</a>"
            }
            list += "</li>\n"
            if AttachmentScheme.isImage(name), !inlineImages.contains(name) {
                thumbs += "<a class=\"thumb\" href=\"\(href)\" title=\"\(e(name))\"><img src=\"\(href)\" alt=\"\(e(name))\"></a>\n"
            }
            if AttachmentScheme.isMarkdown(name), let text = readAttachment(name) {
                docs += "<details class=\"attachment-doc\"\(open ? " open" : "")><summary>\(e(name))</summary>\n"
                docs += "<div class=\"body\">\n\(MarkdownHTML.render(text, thread: thread))</div></details>\n"
            }
        }
        var out = "<footer class=\"attachments\">\n<ul class=\"files\" aria-label=\"Attachments\">\n\(list)</ul>\n"
        if !thumbs.isEmpty { out += "<div class=\"thumbs\">\n\(thumbs)</div>\n" }
        out += docs
        return out + "</footer>\n"
    }
}

/// Which messages start folded: long ones the user has read, except the
/// newest. Long is more than `lineLimit` lines or `characterLimit`
/// characters.
public enum MessageFolding {
    public static let lineLimit = 40
    public static let characterLimit = 3000

    public static func isLong(_ body: String) -> Bool {
        body.count > characterLimit || body.split(separator: "\n", omittingEmptySubsequences: false).count > lineLimit
    }

    public static func startsFolded(body: String, isUnread: Bool, isNewest: Bool) -> Bool {
        !isUnread && !isNewest && isLong(body)
    }
}

extension ThreadDetail {
    /// The messages `me` had not read when the thread had `count` unread:
    /// the last `count` that `me` didn't write (core/unread).
    public func unreadFiles(me: String, count: Int) -> Set<String> {
        guard count > 0 else { return [] }
        return Set(msgs.filter { $0.from != me }.suffix(count).map(\.file))
    }
}
