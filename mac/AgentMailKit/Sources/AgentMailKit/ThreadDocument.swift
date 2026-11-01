// A whole thread as one HTML document for the web view: a strict CSP (no
// scripts, no network, images only as data: URLs), the app's stylesheet
// inlined, every message with its header, and the newest one marked so the
// view can scroll to it.
import Foundation

public enum ThreadDocument {
    public static let contentSecurityPolicy = "default-src 'none'; style-src 'unsafe-inline'; img-src data:"

    /// - Parameters:
    ///   - css: the stylesheet, inlined (a linked one would need a looser CSP).
    ///   - filesDirectory: the thread's attachment directory.
    ///   - formatDate: local time for a message date.
    public static func html(thread: ThreadDetail, me: String, css: String, filesDirectory: URL?,
                            formatDate: (Date) -> String) -> String {
        let e = MarkdownHTML.escape
        var meta = [String]()
        if let n = thread.number { meta.append("#\(n)") }
        meta.append(thread.isOpen ? "open" : "closed")
        if thread.isOpen, let owner = thread.owner { meta.append("waiting on \(owner == me ? "you" : owner)") }
        if let from = thread.from { meta.append("started by \(from)") }

        var body = """
            <header class="thread">
            <h1>\(e(thread.subject))</h1>
            <p class="meta">\(meta.map(e).joined(separator: " · "))</p>
            </header>

            """
        for (i, m) in thread.msgs.enumerated() {
            let newest = i == thread.msgs.count - 1
            var classes = ["msg"]
            if m.from == me { classes.append("mine") }
            if newest { classes.append("newest") }
            let id = newest ? "newest" : "m\(i + 1)"
            body += "<article class=\"\(classes.joined(separator: " "))\" id=\"\(id)\">\n<header>"
            body += "<span class=\"from\">\(e(m.from ?? "?"))</span>"
            body += " <span class=\"arrow\" aria-label=\"to\">→</span> "
            body += "<span class=\"to\">\(e(m.to ?? "?"))</span>"
            if let d = m.date {
                body += " <time datetime=\"\(e(d.formatted(.iso8601)))\">\(e(formatDate(d)))</time>"
            }
            if let s = m.status { body += " <span class=\"status \(e(s))\">\(s == "done" ? "closed" : e(s))</span>" }
            body += "</header>\n"
            if !m.attachments.isEmpty {
                body += "<p class=\"attachments\">"
                body += m.attachments.map { ref -> String in
                    let name = ref.hasPrefix("files/") ? String(ref.dropFirst(6)) : ref
                    if let href = MarkdownHTML.safeDestination(ref, filesDirectory: filesDirectory) {
                        return "<a href=\"\(e(href))\">\(e(name))</a>"
                    }
                    return e(name)
                }.joined(separator: ", ")
                body += "</p>\n"
            }
            body += "<div class=\"body\">\n\(MarkdownHTML.render(m.body, filesDirectory: filesDirectory))</div>\n"
            body += "</article>\n"
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
            \(body)</body>
            </html>

            """
    }
}
