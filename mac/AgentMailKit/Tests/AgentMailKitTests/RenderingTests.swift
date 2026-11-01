import Foundation
import Testing
@testable import AgentMailKit

@Suite struct MarkdownTests {
    let files = URL(filePath: "/store/threads/t1/files")

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
            [att](files/report.txt) [abs](/tmp/x.log) [mail](mailto:a@example.com) <https://example.org>
            """, filesDirectory: files)
        #expect(html.contains("<a href=\"https://example.com/a?b=1&amp;c=%222%22\">ok</a>"))
        #expect(!html.contains("javascript:"))
        #expect(!html.contains("data:text"))
        #expect(html.contains(">bad<") || html.contains(" bad "))
        #expect(html.contains("<a href=\"file:///store/threads/t1/files/report.txt\">att</a>"))
        #expect(html.contains("<a href=\"file:///tmp/x.log\">abs</a>"))
        #expect(html.contains("<a href=\"mailto:a@example.com\">mail</a>"))
        #expect(html.contains("<a href=\"https://example.org\">https://example.org</a>"))
    }

    @Test func attachmentsCannotEscapeTheirDirectory() {
        #expect(MarkdownHTML.safeDestination("files/../../etc/passwd", filesDirectory: files) == nil)
        #expect(MarkdownHTML.safeDestination("files/x", filesDirectory: nil) == nil)
    }

    @Test func imagesBecomeLinks() {
        let html = MarkdownHTML.render("![chart](files/chart.png) ![t](https://t.example/p.gif)", filesDirectory: files)
        #expect(!html.contains("<img"))
        #expect(html.contains("<a class=\"image\" href=\"file:///store/threads/t1/files/chart.png\">[chart]</a>"))
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

@Suite struct ThreadDocumentTests {
    @Test func wholeThreadIsOneSafeDocument() throws {
        let th = try AgentMailJSON.decodeThread(Fixture.data("read"))
        let html = ThreadDocument.html(thread: th, me: "user", css: "body{}", filesDirectory: nil) { _ in "today" }
        #expect(html.contains("content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\""))
        #expect(html.contains("<meta name=\"color-scheme\" content=\"light dark\">"))
        #expect(!html.contains("<script"))
        #expect(html.contains("&lt;script&gt;"))
        #expect(html.components(separatedBy: "<article").count == 3)
        #expect(html.contains("<article class=\"msg newest\" id=\"newest\">"))
        #expect(html.contains("id=\"m1\""))
        #expect(html.contains("waiting on you"))
        #expect(html.contains("<table>"))
    }

    @Test func myMessagesAreMarked() {
        let th = ThreadDetail(id: "t", subject: "S <x>", from: "user", owner: "infra", msgs: [
            Message(file: "a.md", from: "user", to: "infra", date: nil, body: "hi"),
            Message(file: "b.md", from: "infra", to: "user", date: nil, status: "done",
                    attachments: ["files/log.txt"], body: "done"),
        ])
        let html = ThreadDocument.html(thread: th, me: "user", css: "", filesDirectory: URL(filePath: "/s/files")) { _ in "" }
        #expect(html.contains("<h1>S &lt;x&gt;</h1>"))
        #expect(html.contains("class=\"msg mine\""))
        #expect(html.contains("<span class=\"status done\">closed</span>"))
        #expect(html.contains("<a href=\"file:///s/files/log.txt\">log.txt</a>"))
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
