// A whole thread as one HTML document for the web view: a strict CSP (no
// scripts, no network; images only from the attachment scheme), the app's
// stylesheet inlined, a header (subject, state, Reply and Forward, links to
// the thread it forwards and the forwards of it), and every message as a
// card with its header (from → to, date, Open, Reply and Forward buttons),
// its body, and its attachments (links, image thumbnails, Markdown files
// rendered in a collapsible section). Long read messages start folded with
// the middle in a <details>, which works without JavaScript; unread ones and
// the newest never do. The newest is marked so the view can scroll to it.
// A forward's preview is the same cards without the header or buttons.
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
    ///   - forwardedFrom: the thread this one forwards, linked from the header
    ///     (a bare id when the index doesn't know it).
    ///   - forwards: the threads that forward this one, linked from the header.
    ///   - formatDate: local time for a message date.
    public static func html(thread: ThreadDetail, me: String, css: String, expanded: Set<String> = [],
                            forwardedFrom: ThreadSummary? = nil, forwards: [ThreadSummary] = [],
                            readAttachment: (String) -> String? = { _ in nil },
                            formatDate: (Date) -> String) -> String {
        let e = MarkdownHTML.escape
        var meta = [String]()
        if let n = thread.number { meta.append("#\(n)") }
        meta.append(thread.isOpen ? "open" : "closed")
        if thread.isOpen, let owner = thread.owner { meta.append("waiting on \(owner == me ? "you" : owner)") }
        if let from = thread.from { meta.append("started by \(from)") }
        var buttons = ""
        if let reply = LinkPolicy.actionURL(.reply, thread: thread.id) {
            buttons += " <a class=\"button\" href=\"\(e(reply.absoluteString))\" title=\"Reply (⌘R)\">Reply</a>"
        }
        if let forward = LinkPolicy.actionURL(.forward, thread: thread.id) {
            let tip = thread.isOpen ? "Hand the thread on to someone else (⇧⌘F)" : "Forward the thread in a new thread (⇧⌘F)"
            buttons += " <a class=\"button\" href=\"\(e(forward.absoluteString))\" title=\"\(tip)\">Forward</a>"
        }

        var body = """
            <header class="thread">
            <h1>\(e(thread.subject))</h1>
            <p class="meta"><span>\(meta.map(e).joined(separator: " · "))</span>\(buttons)</p>
            \(related(thread: thread.id, forwardedFrom: forwardedFrom, forwards: forwards))</header>

            """
        for (i, m) in thread.msgs.enumerated() {
            let newest = i == thread.msgs.count - 1
            body += card(m, index: i, thread: thread.id, me: me, newest: newest, unread: expanded.contains(m.file),
                         threadIsOpen: thread.isOpen, readAttachment: readAttachment, formatDate: formatDate)
        }
        return document(title: thread.subject, css: css, body: body)
    }

    /// What a forward of `thread` carries, for the new thread's preview: its
    /// messages (or only `message`, a file name) as cards without buttons,
    /// none folded or marked unread, and no thread header (the draft shows
    /// its own). Attachment links name the source thread's files.
    public static func forwardPreview(thread: ThreadDetail, message: String?, me: String, css: String,
                                      readAttachment: (String) -> String? = { _ in nil },
                                      formatDate: (Date) -> String) -> String {
        var body = ""
        for (i, m) in thread.msgs.enumerated() where message == nil || m.file == message {
            body += card(m, index: i, thread: thread.id, me: me, newest: false, unread: false, preview: true,
                         readAttachment: readAttachment, formatDate: formatDate)
        }
        return document(title: thread.subject, css: css, body: body)
    }

    /// The header's links to related threads: the one this forwards, and the
    /// forwards of this one ("Forwarded to infra as #45").
    static func related(thread: String, forwardedFrom: ThreadSummary?, forwards: [ThreadSummary]) -> String {
        let e = MarkdownHTML.escape
        func link(_ t: ThreadSummary, _ text: String) -> String {
            guard let url = LinkPolicy.actionURL(.show, thread: thread, item: t.id) else { return e(text) }
            return "<a class=\"thread-link\" href=\"\(e(url.absoluteString))\" title=\"Show \(e(t.id))\">\(e(text))</a>"
        }
        var parts = [String]()
        if let src = forwardedFrom {
            let name = [src.number.map { "#\($0)" }, src.subject.isEmpty ? nil : src.subject].compactMap { $0 }
            parts.append("Forwarded from " + link(src, name.isEmpty ? src.id : name.joined(separator: " ")))
        }
        if !forwards.isEmpty {
            let links = forwards.map { t in
                link(t, "\(t.firstRecipient ?? "?") as " + (t.number.map { "#\($0)" } ?? "“\(t.subject)”"))
            }
            parts.append("Forwarded to " + links.joined(separator: ", "))
        }
        guard !parts.isEmpty else { return "" }
        return "<p class=\"related\">" + parts.map { "<span>\($0)</span>" }.joined() + "</p>\n"
    }

    static func document(title: String, css: String, body: String) -> String {
        """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="\(contentSecurityPolicy)">
        <meta name="color-scheme" content="light dark">
        <title>\(MarkdownHTML.escape(title))</title>
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

    /// One message. A preview card has no buttons and is never folded.
    static func card(_ m: Message, index i: Int, thread: String, me: String, newest: Bool, unread: Bool,
                     preview: Bool = false, threadIsOpen: Bool = true,
                     readAttachment: (String) -> String?, formatDate: (Date) -> String) -> String {
        let e = MarkdownHTML.escape
        let folded = !preview && MessageFolding.startsFolded(body: m.body, isUnread: unread, isNewest: newest)
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
        if !preview { out += actions(m, thread: thread, me: me, threadIsOpen: threadIsOpen) }
        out += "</header>\n"

        if let folded {
            out += "<div class=\"body\">\n\(folded.head)"
            out += "<details class=\"fold\"><summary><span class=\"more\">Show \(folded.hiddenLines) more lines</span>"
            out += "<span class=\"less\">Hide \(folded.hiddenLines) lines</span></summary>\n"
            out += "\(folded.hidden)</details>\n\(folded.tail)</div>\n"
        } else if let rendered {
            out += "<div class=\"body\">\n\(rendered.html)</div>\n"
        }
        out += attachments(m.attachments, thread: thread, inlineImages: inlineImages, open: preview || unread || newest,
                           readAttachment: readAttachment)
        out += "</article>\n"
        return out
    }

    /// A card's Open, Reply and Forward buttons. Forward on an open thread
    /// hands the whole thread on, as the thread's own Forward does; on a
    /// closed one it forwards this message in a new thread.
    static func actions(_ m: Message, thread: String, me: String, threadIsOpen: Bool) -> String {
        let e = MarkdownHTML.escape
        var out = "<span class=\"actions\">"
        if let open = LinkPolicy.actionURL(.open, thread: thread, item: m.file) {
            out += "<a class=\"button\" href=\"\(e(open.absoluteString))\" title=\"Open the message file in your Markdown app\">Open</a>"
        }
        if let reply = LinkPolicy.actionURL(.reply, thread: thread, item: m.file) {
            let to = m.from == me ? m.to : m.from
            out += "<a class=\"button\" href=\"\(e(reply.absoluteString))\" title=\"Reply to \(e(to ?? "?"))\">Reply</a>"
        }
        if let forward = LinkPolicy.actionURL(.forward, thread: thread, item: m.file) {
            let tip = threadIsOpen ? "Hand the thread on to someone else" : "Forward this message in a new thread"
            out += "<a class=\"button\" href=\"\(e(forward.absoluteString))\" title=\"\(tip)\">Forward</a>"
        }
        return out + "</span>"
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
