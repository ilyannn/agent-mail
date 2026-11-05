import Foundation
import Testing
@testable import AgentMailKit

@Suite struct MarkdownTests {
    let thread = "2026-10-31-t1"

    @Test func rawHTMLIsShownAsText() {
        let html = MarkdownHTML.render("Hi <script>alert(1)</script> there\n\n<div onclick=\"x()\">block</div>\n")
        #expect(!html.contains("<script"))
        #expect(!html.contains("<div"))
        #expect(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        #expect(html.contains("&lt;div onclick=&quot;x()&quot;&gt;block&lt;/div&gt;"))
    }

    @Test func textAndCodeAreEscaped() {
        let html = MarkdownHTML.render("a < b & c\n\n`<b>`\n\n```html\n<img src=x onerror=y>\n```\n\n# T <i>\n")
        #expect(html.contains("<p>a &lt; b &amp; c</p>"))
        #expect(html.contains("<code>&lt;b&gt;</code>"))
        #expect(html.contains("<pre><code class=\"language-html\">&lt;img src=x onerror=y&gt;\n</code></pre>"))
        #expect(!html.contains("<img"))
        #expect(!html.contains("<i>"))
    }

    @Test func punctuationIsKeptAsWritten() {
        let html = MarkdownHTML.render("Use \"just deploy\" -- don't use --force...")
        #expect(html.contains("Use &quot;just deploy&quot; -- don&#39;t use --force..."))
        #expect(MarkdownHTML.plainPreview("say \"hi\" -- now") == "say \"hi\" -- now")
    }

    @Test func linksAreFiltered() {
        let html = MarkdownHTML.render("""
            [ok](https://example.com/a?b=1&c="2") [bad](javascript:alert(1)) [data](data:text/html,x)
            [att](files/report%20v2.txt) [abs](/tmp/x.log) [file](file:///etc/hosts) [mail](mailto:a@example.com)
            <https://example.org>
            """, thread: thread)
        #expect(html.contains("<a href=\"https://example.com/a?b=1&amp;c=%222%22\">ok</a>"))
        #expect(!html.contains("javascript:"))
        #expect(!html.contains("data:text"))
        #expect(html.contains(">bad<") || html.contains(" bad "))
        #expect(html.contains("<a href=\"agent-mail-file:///2026-10-31-t1/report%20v2.txt\">att</a>"))
        // Local paths are text with a tooltip: the view opens no file:// links.
        #expect(html.contains("<span class=\"local-path\" title=\"/tmp/x.log\">abs</span>"))
        #expect(html.contains("<span class=\"local-path\" title=\"file:///etc/hosts\">file</span>"))
        #expect(!html.contains("href=\"file:"))
        #expect(html.contains("<a href=\"mailto:a@example.com\">mail</a>"))
        #expect(html.contains("<a href=\"https://example.org\">https://example.org</a>"))
    }

    @Test func attachmentsCannotEscapeTheirDirectory() {
        #expect(MarkdownHTML.safeDestination("files/../../etc/passwd", thread: thread) == nil)
        #expect(MarkdownHTML.safeDestination("files/..%2F..%2Fetc%2Fpasswd", thread: thread) == nil)
        #expect(MarkdownHTML.safeDestination("files/sub/x.png", thread: thread) == nil)
        #expect(MarkdownHTML.safeDestination("files/x", thread: nil) == nil)
    }

    @Test func attachmentImagesAreShownInline() {
        let r = MarkdownHTML.renderBody("![chart](files/chart.png) ![t](https://t.example/p.gif) ![x](files/notes.md)",
                                        thread: thread)
        #expect(r.html.contains("<a class=\"inline-image\" href=\"agent-mail-file:///2026-10-31-t1/chart.png\" "
            + "title=\"chart.png\"><img src=\"agent-mail-file:///2026-10-31-t1/chart.png\" alt=\"chart\"></a>"))
        #expect(r.inlineImages == ["chart.png"])
        // Remote images stay links (nothing loads from the network), and a
        // non-image attachment is a link too.
        #expect(r.html.contains("<a class=\"image\" href=\"https://t.example/p.gif\">[t]</a>"))
        #expect(r.html.contains("<a class=\"image\" href=\"agent-mail-file:///2026-10-31-t1/notes.md\">[x]</a>"))
        #expect(r.html.components(separatedBy: "<img").count == 2)
        // Without a thread there is nothing to load from.
        #expect(!MarkdownHTML.render("![c](files/c.png)").contains("<img"))
        // Alt text is escaped.
        #expect(MarkdownHTML.render("![a\"b<c](files/c.png)", thread: thread).contains("alt=\"a&quot;b&lt;c\""))
    }

    @Test func gfmTablesAndTasks() {
        let html = MarkdownHTML.render("""
            | host | load |
            |:-----|-----:|
            | a    | 0.3  |

            - [x] done
            - [ ] todo

            ~~old~~
            """)
        #expect(html.contains("<th class=\"left\">host</th>"))
        #expect(html.contains("<td class=\"right\">0.3</td>"))
        #expect(html.contains("☑"))
        #expect(html.contains("☐"))
        #expect(html.contains("<del>old</del>"))
    }

    @Test func plainPreview() {
        let p = MarkdownHTML.plainPreview("Please deploy **website** v2.\n\n- build is green\n- needs `nginx -s reload`")
        #expect(p == "Please deploy website v2. build is green needs nginx -s reload")
        let long = MarkdownHTML.plainPreview(String(repeating: "word ", count: 100), limit: 20)
        #expect(long.count == 20)
        #expect(long.hasSuffix("…"))
    }
}

@Suite struct FoldingTests {
    static let paragraphs = (1...30).map { "Paragraph \($0)." }.joined(separator: "\n\n")

    @Test func longReadMessagesFoldUnlessUnreadOrNewest() {
        let long = Self.paragraphs
        #expect(MessageFolding.isLong(long))
        #expect(MessageFolding.startsFolded(body: long, isUnread: false, isNewest: false))
        #expect(!MessageFolding.startsFolded(body: long, isUnread: true, isNewest: false))
        #expect(!MessageFolding.startsFolded(body: long, isUnread: false, isNewest: true))
        #expect(!MessageFolding.startsFolded(body: "short", isUnread: false, isNewest: false))
        // Over the character limit on few lines is long too.
        #expect(MessageFolding.isLong(String(repeating: "x", count: 3001)))
        #expect(!MessageFolding.isLong(String(repeating: "x\n", count: 39)))
    }

    @Test func foldKeepsTheStartAndTheEnd() throws {
        let f = try #require(MarkdownHTML.renderFolded(Self.paragraphs, thread: nil))
        #expect(f.head.hasPrefix("<p>Paragraph 1.</p>"))
        #expect(f.head.contains("Paragraph 12.") && !f.head.contains("Paragraph 13."))
        #expect(f.hidden.hasPrefix("<p>Paragraph 13.</p>"))
        #expect(f.tail.hasSuffix("<p>Paragraph 30.</p>\n"))
        #expect(f.tail.contains("Paragraph 25.") && !f.tail.contains("Paragraph 24."))
        #expect(f.hiddenLines == 12)
    }

    @Test func foldSplitsListsAndCodeBlocksCleanly() throws {
        let list = (1...40).map { "\($0). item" }.joined(separator: "\n")
        let f = try #require(MarkdownHTML.renderFolded(list, thread: nil))
        #expect(f.head.hasPrefix("<ol>\n<li>"))
        #expect(f.head.hasSuffix("</ol>\n"))
        #expect(f.hidden.hasPrefix("<ol start=\"13\">\n<li>"))
        #expect(f.tail.hasPrefix("<ol start=\"35\">\n"))

        let code = "Intro.\n\n```sh\n" + (1...50).map { "echo \($0)" }.joined(separator: "\n") + "\n```\n\nBye."
        let c = try #require(MarkdownHTML.renderFolded(code, thread: nil))
        #expect(c.head.hasPrefix("<p>Intro.</p>\n<pre><code class=\"language-sh\">echo 1\n"))
        #expect(c.head.hasSuffix("</code></pre>\n"))
        #expect(c.hidden.hasPrefix("<pre><code class=\"language-sh\">echo 12\n"))
        #expect(c.tail.hasSuffix("echo 50\n</code></pre>\n<p>Bye.</p>\n"))
    }

    @Test func shortBodiesDontFold() {
        #expect(MarkdownHTML.renderFolded("one\n\ntwo\n\nthree", thread: nil) == nil)
    }

    @Test func unreadFilesAreTheLastFromOthers() {
        let th = ThreadDetail(id: "t", subject: "s", msgs: [
            Message(file: "1.md", from: "infra", to: "user", date: nil, body: "a"),
            Message(file: "2.md", from: "infra", to: "user", date: nil, body: "b"),
            Message(file: "3.md", from: "user", to: "infra", date: nil, body: "c"),
            Message(file: "4.md", from: "infra", to: "user", date: nil, body: "d"),
        ])
        #expect(th.unreadFiles(me: "user", count: 2) == ["2.md", "4.md"])
        #expect(th.unreadFiles(me: "user", count: 0) == [])
    }

    @Test func unreadFilesAllowForAStatusOfAnotherCopy() {
        let th = ThreadDetail(id: "t", subject: "s", msgs: [
            Message(file: "1.md", from: "infra", to: "user", date: nil, body: "a"),
            Message(file: "2.md", from: "user", to: "infra", date: nil, body: "b"),
            Message(file: "3.md", from: "infra", to: "user", date: nil, body: "c"),
            Message(file: "4.md", from: "user", to: "infra", date: nil, body: "d"),
            Message(file: "5.md", from: "infra", to: "user", date: nil, body: "e"),
        ])
        func summary(count: Int, unread: Int) -> ThreadSummary {
            ThreadSummary(id: "t", subject: "s", count: count, unread: unread)
        }
        #expect(th.unreadFiles(me: "user", summary: summary(count: 5, unread: 2)) == ["3.md", "5.md"], "the same copy")
        #expect(th.unreadFiles(me: "user", summary: summary(count: 5, unread: 0)) == [])
        // The status predates 5.md (from infra) and 4.md (mine): 5.md is new too.
        #expect(th.unreadFiles(me: "user", summary: summary(count: 3, unread: 1)) == ["3.md", "5.md"])
        #expect(th.unreadFiles(me: "user", summary: summary(count: 3, unread: 0)) == ["5.md"])
        // A cached copy without the two newest of 7, both unread: nothing it
        // holds is, rather than 3.md and 5.md.
        #expect(th.unreadFiles(me: "user", summary: summary(count: 7, unread: 2)) == [])
        #expect(th.unreadFiles(me: "user", summary: summary(count: 7, unread: 3)) == ["5.md"])
    }
}

@Suite struct ThreadDocumentTests {
    @Test func wholeThreadIsOneSafeDocument() throws {
        let th = try AgentMailJSON.decodeThread(Fixture.data("read"))
        let html = ThreadDocument.html(thread: th, me: "user", css: "body{}") { _ in "today" }
        #expect(html.contains("content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data: agent-mail-file:\""))
        #expect(html.contains("<meta name=\"color-scheme\" content=\"light dark\">"))
        #expect(!html.contains("<script"))
        #expect(html.contains("&lt;script&gt;"))
        #expect(html.components(separatedBy: "<article").count == 3)
        #expect(html.contains("<article class=\"msg newest\" id=\"newest\">"))
        #expect(html.contains("id=\"m1\""))
        #expect(html.contains("waiting on you"))
        #expect(html.contains("<table>"))
        #expect(html.contains("href=\"agent-mail-action:///reply/2026-10-31-deploy-the-new-site\""))
    }

    @Test func cardsHaveOpenAndReplyButtons() {
        let th = ThreadDetail(id: "t", subject: "S <x>", from: "user", owner: "infra", msgs: [
            Message(file: "a.md", from: "user", to: "infra", date: nil, body: "hi"),
            Message(file: "b.md", from: "infra", to: "user", date: nil, status: "done", body: "done"),
        ])
        let html = ThreadDocument.html(thread: th, me: "user", css: "") { _ in "" }
        #expect(html.contains("<h1>S &lt;x&gt;</h1>"))
        #expect(html.contains("class=\"msg mine\""))
        #expect(html.contains("<span class=\"status done\">closed</span>"))
        #expect(html.contains("<a class=\"button\" href=\"agent-mail-action:///open/t/a.md\""))
        #expect(html.contains("href=\"agent-mail-action:///reply/t/b.md\" title=\"Reply to infra\">Reply</a>"))
        // My own message: Reply goes to whom I wrote.
        #expect(html.contains("href=\"agent-mail-action:///reply/t/a.md\" title=\"Reply to infra\">Reply</a>"))
        // A closed thread: a card's Forward starts a new thread with that message.
        var closed = th
        closed.status = "done"
        let done = ThreadDocument.html(thread: closed, me: "user", css: "") { _ in "" }
        #expect(done.contains("href=\"agent-mail-action:///forward/t/b.md\" title=\"Forward this message in a new thread\">Forward</a>"))
        #expect(done.contains("href=\"agent-mail-action:///forward/t\" title=\"Forward the thread in a new thread (⇧⌘F)\">Forward</a>"))
    }

    @Test func forwardOnAnOpenThreadHandsItOn() throws {
        let th = try AgentMailJSON.decodeThread(Fixture.data("read"))
        let html = ThreadDocument.html(thread: th, me: "user", css: "") { _ in "" }
        #expect(html.contains("href=\"agent-mail-action:///forward/2026-10-31-deploy-the-new-site\" "
            + "title=\"Hand the thread on to someone else (⇧⌘F)\">Forward</a>"))
        #expect(html.contains("href=\"agent-mail-action:///forward/2026-10-31-deploy-the-new-site/"
            + "2026-10-31T230503.205Z__website__66ad.md\" title=\"Hand the thread on to someone else\">Forward</a>"))
        #expect(html.components(separatedBy: ">Forward</a>").count == 4, "the thread's and one per card")
        #expect(!html.contains("class=\"related\""), "no forwards either way")
    }

    @Test func headerLinksAForwardToItsSourceAndBack() throws {
        let status = try AgentMailJSON.decodeStatus(Fixture.data("status-forward"))
        let index = MailboxIndex(status: status)
        let fwd = try AgentMailJSON.decodeThread(Fixture.data("read-forward"))
        let source = try #require(index.thread("2026-11-03-rotate-the-certificate"))
        let html = ThreadDocument.html(thread: fwd, me: "user", css: "", forwardedFrom: source) { _ in "" }
        #expect(html.contains("<p class=\"related\"><span>Forwarded from <a class=\"thread-link\" "
            + "href=\"agent-mail-action:///show/2026-11-03-fwd-rotate-the-certificate/2026-11-03-rotate-the-certificate\" "
            + "title=\"Show 2026-11-03-rotate-the-certificate\">#1 Rotate the certificate</a></span></p>"))

        let original = ThreadDetail(id: source.id, number: 1, subject: source.subject, status: "done", msgs: [])
        let back = ThreadDocument.html(thread: original, me: "user", css: "",
                                       forwards: index.forwards(of: source.id)) { _ in "" }
        #expect(back.contains("<span>Forwarded to <a class=\"thread-link\" "
            + "href=\"agent-mail-action:///show/2026-11-03-rotate-the-certificate/2026-11-03-fwd-rotate-the-certificate\" "
            + "title=\"Show 2026-11-03-fwd-rotate-the-certificate\">docs as #2</a>, <a class=\"thread-link\" "
            + "href=\"agent-mail-action:///show/2026-11-03-rotate-the-certificate/2026-11-03-fwd-rotate-the-certificate-2\" "
            + "title=\"Show 2026-11-03-fwd-rotate-the-certificate-2\">infra as #3</a></span>"))

        // A source the index doesn't know: its id, still a link.
        let unknown = ThreadDocument.html(thread: fwd, me: "user", css: "",
                                          forwardedFrom: ThreadSummary(id: "gone", subject: "")) { _ in "" }
        #expect(unknown.contains(">gone</a>"))
    }

    @Test func forwardPreviewIsCardsWithoutButtons() throws {
        let th = ThreadDetail(id: "t", number: 3, subject: "S", status: "done", msgs: [
            Message(file: "1.md", from: "infra", to: "user", date: nil, body: FoldingTests.paragraphs),
            Message(file: "2.md", from: "user", to: "infra", date: nil, attachments: ["files/notes.md"], body: "two"),
        ])
        let all = ThreadDocument.forwardPreview(thread: th, message: nil, me: "user", css: "",
                                                readAttachment: { _ in "# Notes" }) { _ in "" }
        #expect(!all.contains("<header class=\"thread\">"), "the draft has its own header")
        #expect(!all.contains("agent-mail-action:///open"))
        #expect(!all.contains("agent-mail-action:///reply"))
        #expect(!all.contains("agent-mail-action:///forward"))
        #expect(!all.contains("class=\"fold\""), "nothing folded, however long")
        #expect(!all.contains("unread"))
        #expect(all.contains("<article class=\"msg\" id=\"m1\">"))
        #expect(all.contains("<article class=\"msg mine\" id=\"m2\">"))
        #expect(all.contains("<a class=\"file\" href=\"agent-mail-file:///t/notes.md\""), "attachments still open")
        #expect(all.contains("<details class=\"attachment-doc\" open>"))
        let one = ThreadDocument.forwardPreview(thread: th, message: "2.md", me: "user", css: "") { _ in "" }
        #expect(one.components(separatedBy: "<article").count == 2)
        #expect(one.contains("id=\"m2\""))
    }

    @Test func attachmentsAreListedShownAndRendered() {
        let th = ThreadDetail(id: "t", subject: "s", msgs: [
            Message(file: "a.md", from: "infra", to: "user", date: nil,
                    attachments: ["files/shot.png", "files/inline.png", "files/notes.md", "files/log.txt"],
                    body: "See ![the chart](files/inline.png)."),
        ])
        let html = ThreadDocument.html(thread: th, me: "user", css: "",
                                       readAttachment: { $0 == "notes.md" ? "# Notes\n\n<b>x</b> [s](files/shot.png)" : nil }) { _ in "" }
        #expect(html.contains("<a class=\"file\" href=\"agent-mail-file:///t/shot.png\" title=\"Open shot.png\">shot.png</a>"))
        #expect(html.contains("<a class=\"reveal\" href=\"agent-mail-action:///reveal/t/log.txt\""))
        // A thumbnail for the attached image, none for the one shown inline.
        #expect(html.contains("<a class=\"thumb\" href=\"agent-mail-file:///t/shot.png\" title=\"shot.png\">"
            + "<img src=\"agent-mail-file:///t/shot.png\" alt=\"shot.png\"></a>"))
        #expect(!html.contains("class=\"thumb\" href=\"agent-mail-file:///t/inline.png\""))
        #expect(html.contains("<img src=\"agent-mail-file:///t/inline.png\" alt=\"the chart\">"))
        // The Markdown attachment, rendered and escaped, open on the newest message.
        #expect(html.contains("<details class=\"attachment-doc\" open><summary>notes.md</summary>"))
        #expect(html.contains("<h1>Notes</h1>"))
        #expect(html.contains("&lt;b&gt;x&lt;/b&gt;"))
        #expect(!html.contains("thumb\" href=\"agent-mail-file:///t/log.txt"))
    }

    @Test func longReadMessagesStartFolded() {
        let long = FoldingTests.paragraphs
        let th = ThreadDetail(id: "t", subject: "s", msgs: [
            Message(file: "1.md", from: "infra", to: "user", date: nil, body: long),
            Message(file: "2.md", from: "infra", to: "user", date: nil, body: long),
            Message(file: "3.md", from: "infra", to: "user", date: nil, body: long),
        ])
        let html = ThreadDocument.html(thread: th, me: "user", css: "", unread: ["2.md"]) { _ in "" }
        #expect(html.contains("<article class=\"msg folded\" id=\"m1\">"))
        #expect(html.contains("<article class=\"msg unread\" id=\"m2\">"))
        #expect(html.contains("<article class=\"msg newest\" id=\"newest\">"))
        #expect(html.components(separatedBy: "<details class=\"fold\">").count == 2)
        #expect(html.contains("<summary><span class=\"more\">Show 12 more lines</span><span class=\"less\">Hide 12 lines</span></summary>"))
    }

    @Test func onlyWhatIsNewNowIsMarkedUnread() {
        let long = FoldingTests.paragraphs
        let th = ThreadDetail(id: "t", subject: "s", msgs: [
            Message(file: "1.md", from: "infra", to: "user", date: nil, body: long),
            Message(file: "2.md", from: "infra", to: "user", date: nil, body: long),
            Message(file: "3.md", from: "infra", to: "user", date: nil, body: long),
            Message(file: "4.md", from: "infra", to: "user", date: nil, body: long),
        ])
        // 1.md was new when the thread was first shown; now 3.md is.
        let html = ThreadDocument.html(thread: th, me: "user", css: "", unread: ["3.md"],
                                       expanded: ["1.md", "3.md"]) { _ in "" }
        #expect(html.contains("<article class=\"msg\" id=\"m1\">"), "unfolded, not marked: the view doesn't scroll to it")
        #expect(html.contains("<article class=\"msg folded\" id=\"m2\">"))
        #expect(html.contains("<article class=\"msg unread\" id=\"m3\">"))
        #expect(html.components(separatedBy: "class=\"dot\"").count == 2, "one unread dot")
        #expect(html.components(separatedBy: "<details class=\"fold\">").count == 2, "only 2.md folds")
    }
}

@Suite struct LinkTests {
    let files = URL(filePath: "/store/threads/t1/files")

    @Test func attachmentURLsMapIntoTheThreadsFiles() throws {
        let url = try #require(AttachmentScheme.url(thread: "t1", name: "a b?#.png"))
        #expect(url.absoluteString == "agent-mail-file:///t1/a%20b%3F%23.png")
        #expect(AttachmentScheme.name(in: url, thread: "t1") == "a b?#.png")
        #expect(AttachmentScheme.fileURL(for: url, thread: "t1", filesDirectory: files)?.path
            == "/store/threads/t1/files/a b?#.png")
    }

    @Test func traversalAndOtherThreadsAreRejected() {
        let bad = [
            "agent-mail-file:///t1/../x",
            "agent-mail-file:///t1/..%2F..%2Fsecret",
            "agent-mail-file:///t1/%2E%2E",
            "agent-mail-file:///t1/sub/x.png",
            "agent-mail-file:///t1/",
            "agent-mail-file:///t2/x.png",
            "agent-mail-file://host/t1/x.png",
            "agent-mail-file:///t1/x%00.png",
            "file:///store/threads/t1/files/x.png",
            "agent-mail-file:t1/x.png",
        ]
        for s in bad {
            let url = URL(string: s)
            #expect(url.flatMap { AttachmentScheme.fileURL(for: $0, thread: "t1", filesDirectory: files) } == nil, "\(s)")
        }
        #expect(AttachmentScheme.url(thread: "t1", name: "..") == nil)
        #expect(AttachmentScheme.url(thread: "t1", name: "a/b") == nil)
        #expect(AttachmentScheme.url(thread: "../t", name: "x") == nil)
    }

    @Test func symlinksOutOfTheDirectoryAreRejected() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "agent-mail-links-\(UUID().uuidString)")
        let dir = root.appending(path: "files")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try Data("secret".utf8).write(to: root.appending(path: "secret.png"))
        try Data("ok".utf8).write(to: dir.appending(path: "ok.png"))
        try FileManager.default.createSymbolicLink(at: dir.appending(path: "link.png"),
                                                   withDestinationURL: root.appending(path: "secret.png"))
        #expect(AttachmentScheme.file(named: "ok.png", in: dir) != nil)
        #expect(AttachmentScheme.file(named: "link.png", in: dir) == nil)
    }

    @Test func kindsAndTypes() {
        #expect(AttachmentScheme.isImage("a.PNG") && AttachmentScheme.isImage("b.heic") && AttachmentScheme.isImage("c.svg"))
        #expect(!AttachmentScheme.isImage("notes.md") && !AttachmentScheme.isImage("png"))
        #expect(AttachmentScheme.isMarkdown("notes.md"))
        #expect(AttachmentScheme.mimeType(forName: "a.png") == "image/png")
        #expect(AttachmentScheme.mimeType(forName: "a.svg") == "image/svg+xml")
        #expect(AttachmentScheme.mimeType(forName: "noext") == "application/octet-stream")
    }

    @Test func linkPolicy() {
        func c(_ s: String, click: Bool = true) -> ThreadLink {
            LinkPolicy.classify(URL(string: s), isLinkClick: click, thread: "t1")
        }
        #expect(c("about:blank", click: false) == .loadDocument)
        #expect(c("https://example.com/x", click: false) == .ignore)
        #expect(c("https://example.com/x") == .openExternally(URL(string: "https://example.com/x")!))
        #expect(c("http://example.com") == .openExternally(URL(string: "http://example.com")!))
        #expect(c("mailto:a@example.com") == .openExternally(URL(string: "mailto:a@example.com")!))
        #expect(c("agent-mail-file:///t1/a%20b.png") == .openAttachment("a b.png"))
        #expect(c("agent-mail-file:///t2/a.png") == .ignore)
        #expect(c("agent-mail-file:///t1/../a.png") == .ignore)
        #expect(c("agent-mail-action:///open/t1/m.md") == .openMessage("m.md"))
        #expect(c("agent-mail-action:///reply/t1/m.md") == .reply(toMessage: "m.md"))
        #expect(c("agent-mail-action:///reply/t1") == .reply(toMessage: nil))
        #expect(c("agent-mail-action:///reveal/t1/a.png") == .revealAttachment("a.png"))
        #expect(c("agent-mail-action:///forward/t1/m.md") == .forward(message: "m.md"))
        #expect(c("agent-mail-action:///forward/t1") == .forward(message: nil))
        #expect(c("agent-mail-action:///show/t1/t2") == .openThread("t2"))
        #expect(c("agent-mail-action:///show/t1") == .ignore, "show needs a thread")
        #expect(c("agent-mail-action:///show/t1/t1") == .ignore, "not the thread on screen")
        #expect(c("agent-mail-action:///show/t1/..") == .ignore)
        #expect(c("agent-mail-action:///show/t2/t3") == .ignore, "only from the thread on screen")
        #expect(c("agent-mail-action:///open/t1") == .ignore)
        #expect(c("agent-mail-action:///delete/t1/m.md") == .ignore)
        #expect(c("agent-mail-action:///open/t2/m.md") == .ignore)
        #expect(c("agent-mail-action:///reveal/t1/..") == .ignore)
        #expect(c("file:///etc/hosts") == .ignore)
        #expect(c("javascript:alert(1)") == .ignore)
        #expect(c("data:text/html,x") == .ignore)
        #expect(c("x-apple.systempreferences:foo") == .ignore)
        #expect(LinkPolicy.classify(URL(string: "agent-mail-file:///t1/a.png"), isLinkClick: true, thread: nil) == .ignore)
        #expect(LinkPolicy.actionURL(.open, thread: "t1", item: "m.md")?.absoluteString == "agent-mail-action:///open/t1/m.md")
        #expect(LinkPolicy.actionURL(.show, thread: "t1", item: "t2")?.absoluteString == "agent-mail-action:///show/t1/t2")
        #expect(LinkPolicy.actionURL(.forward, thread: "t1")?.absoluteString == "agent-mail-action:///forward/t1")
    }

    @Test func executablesAreNotOpened() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "agent-mail-open-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let png = dir.appending(path: "a.png")
        try Data().write(to: png)
        let script = dir.appending(path: "run")
        try Data("#!/bin/sh\n".utf8).write(to: script)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: script.path)
        #expect(LinkPolicy.isSafeToOpen(png))
        #expect(!LinkPolicy.isSafeToOpen(script))
        #expect(!LinkPolicy.isSafeToOpen(dir.appending(path: "x.command")))
        #expect(!LinkPolicy.isSafeToOpen(dir.appending(path: "x.app")))
        #expect(!LinkPolicy.isSafeToOpen(dir.appending(path: "x.sh")))
        #expect(LinkPolicy.isSafeToOpen(dir.appending(path: "notes.md")))
    }
}

@Suite struct TextZoomTests {
    @Test func steps() {
        #expect(TextZoom.bigger(than: 1) == 1.1)
        #expect(TextZoom.smaller(than: 1) == 0.9)
        #expect(TextZoom.bigger(than: 1.3) == 1.5)
        #expect(TextZoom.smaller(than: 1.3) == 1.25)
        #expect(TextZoom.bigger(than: 3) == 3)
        #expect(TextZoom.smaller(than: 0.5) == 0.5)
        #expect(TextZoom.clamp(9) == 3)
        #expect(TextZoom.clamp(.nan) == 1)
    }
}

@Suite struct QuotingTests {
    @Test func quotesLikeTheCLI() {
        #expect(Quoting.quote("one\n\ntwo") == "> one\n>\n> two")
        #expect(Quoting.quote("\n\n") == "")
        #expect(Quoting.replyPrefill(selection: "a line") == "> a line\n\n")
        #expect(Quoting.replyPrefill(selection: "") == "")
    }
}
