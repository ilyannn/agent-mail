// The main window's parts against a store that never runs the CLI (its path
// points nowhere): the menus for Forward and the participant filter, the
// thread list's scope bar and draft row, and the thread view's three modes.
import AgentMailKit
import AppKit
import Foundation
import Testing
@testable import AgentMail

@MainActor @Suite struct MainWindowTests {
    /// infra started a (waiting on user) and c (closed); user started b
    /// (waiting on infra).
    static let status = MailStatus(now: Date(timeIntervalSince1970: 4000), me: "user", threads: [
        ThreadSummary(id: "a", number: 1, subject: "Deploy", from: "infra", lastFrom: "infra", owner: "user",
                      updated: Date(timeIntervalSince1970: 3000), participants: ["infra", "user"],
                      flow: ["infra", "user"], listener: "user"),
        ThreadSummary(id: "b", number: 2, subject: "Docs", from: "user", lastFrom: "user", owner: "infra",
                      updated: Date(timeIntervalSince1970: 2000), participants: ["infra", "user"],
                      flow: ["user", "infra"], listener: "none"),
        ThreadSummary(id: "c", number: 3, subject: "Old", from: "infra", lastFrom: "website", owner: "infra",
                      status: "done", updated: Date(timeIntervalSince1970: 1000), participants: ["infra", "website"],
                      flow: ["infra", "website"]),
    ])

    func withStore(_ body: (MailStore) async throws -> Void) async rethrows {
        let suite = "agent-mail-tests-\(UUID().uuidString)"
        defer { UserDefaults.standard.removePersistentDomain(forName: suite) }
        let settings = AppSettings(defaults: UserDefaults(suiteName: suite)!)
        settings.cliPath = "/nonexistent/agent-mail"
        let store = MailStore(settings: settings, location: StoreLocation(url: URL(filePath: "/tmp/agent-mail-tests-no-store"),
                                                                          source: .environment))
        store.debugShow(Self.status)
        try await body(store)
    }

    func show(_ vc: NSViewController, size: NSSize = NSSize(width: 600, height: 500)) -> NSWindow {
        let window = NSWindow(contentViewController: vc)
        window.isReleasedWhenClosed = false
        window.setContentSize(size)
        window.layoutIfNeeded()
        return window
    }

    /// Waits, up to a second, for the observers to catch up with the store.
    func settle(until condition: () -> Bool) async {
        var waited = 0
        while !condition(), waited < 100 {
            try? await Task.sleep(for: .milliseconds(10))
            waited += 1
        }
    }

    func rowIDs(_ list: ThreadListViewController) -> [String] {
        list.debugRows().filter { $0.hasPrefix("row") }.map { $0.components(separatedBy: " | ")[0] }
    }

    @Test func viewMenuHasTheParticipantFilters() {
        let items = MainMenu.filterItems()
        #expect(items.map(\.title) == ["Participant Filter", "All", "Sent", "Received", "Waiting"])
        #expect(items.first?.action == nil, "a section header")
        #expect(items.compactMap { ParticipantFilter(menuItem: $0) } == ParticipantFilter.allCases)
        #expect(items.dropFirst().allSatisfy { $0.action == #selector(MailActions.setParticipantFilter(_:)) })
        #expect(items[2].toolTip == "Threads the participant started")
        #expect(MainMenu.viewMenu().items.contains { $0.title == "Received" })
    }

    @Test func messageMenuHasForward() throws {
        let menu = MainMenu.messageMenu()
        #expect(menu.items.map(\.title) == ["Send", "", "Reply", "Forward", "Close Thread", "", "Copy Thread ID",
                                            "Show in Finder"])
        let forward = try #require(menu.items.first { $0.title == "Forward" })
        #expect(forward.keyEquivalent == "f")
        #expect(forward.keyEquivalentModifierMask == [.command, .shift])
        #expect(forward.action == #selector(MailActions.forwardThread(_:)))
    }

    @Test func scopeBarShowsForParticipantsAndFilters() async {
        await withStore { store in
            let list = ThreadListViewController(store: store)
            let window = show(list, size: NSSize(width: 300, height: 400))
            defer { window.close() }
            #expect(list.debugScopeBar() == "scope: hidden", "the Inbox")
            #expect(rowIDs(list) == ["row  a"])

            store.mailbox = .agent("infra")
            await settle { list.debugScopeBar() != "scope: hidden" }
            #expect(list.debugScopeBar() == "scope: All* Sent Received Waiting | tips: "
                + "Every thread infra takes part in (3) / Threads infra started (2) / "
                + "Threads others started that infra takes part in (1) / Open threads waiting on infra (1)")
            #expect(rowIDs(list) == ["row  a", "row  b", "row  c"])

            store.participantFilter = .sent
            await settle { rowIDs(list).count == 2 }
            #expect(rowIDs(list) == ["row  a", "row  c"])
            #expect(list.debugScopeBar().hasPrefix("scope: All Sent* Received Waiting"))
            store.participantFilter = .waiting
            await settle { rowIDs(list).count == 1 }
            #expect(rowIDs(list) == ["row  b"])

            store.mailbox = .agent("website")
            await settle { rowIDs(list).isEmpty }
            #expect(list.debugRows().last == "emptyLabel: Nothing waiting on website", "the filter stays")
            store.mailbox = .agent("user")
            await settle { !rowIDs(list).isEmpty }
            #expect(rowIDs(list) == ["row  a"])
            #expect(list.debugScopeBar().contains("Open threads waiting on you (1)"))

            store.mailbox = .allMail
            await settle { list.debugScopeBar() == "scope: hidden" }
            #expect(rowIDs(list) == ["row  a", "row  b", "row  c"], "All Mail isn't filtered")
        }
    }

    @Test func filterDropsASelectionItHides() async {
        await withStore { store in
            store.mailbox = .agent("infra")
            store.select(threadID: "b")
            store.participantFilter = .received
            #expect(store.selectedThreadID == "b")
            store.participantFilter = .sent
            #expect(store.selectedThreadID == nil)
            #expect(store.shownThreads.map(\.id) == ["a", "c"])
        }
    }

    @Test func theDraftIsARowAtTheTopOfEveryMailbox() async {
        await withStore { store in
            store.mailbox = .allMail
            store.select(threadID: "b")
            let list = ThreadListViewController(store: store)
            let window = show(list, size: NSSize(width: 300, height: 400))
            defer { window.close() }
            #expect(rowIDs(list) == ["row  a", "row* b", "row  c"])

            store.startNewMessage(to: "infra")
            await settle { rowIDs(list).count == 4 }
            #expect(rowIDs(list) == ["row* /draft", "row  a", "row  b", "row  c"])
            #expect(list.debugRows()[1] == "row* /draft | Draft, New Message, to infra")
            #expect(store.selectedThreadID == nil && store.isShowingDraft)

            store.select(threadID: "c")
            await settle { rowIDs(list).last == "row* c" }
            #expect(rowIDs(list) == ["row  /draft", "row  a", "row  b", "row* c"], "the draft stays")

            store.mailbox = .inbox
            await settle { rowIDs(list).count == 2 }
            #expect(rowIDs(list) == ["row  /draft", "row  a"])

            store.showDraft()
            store.editDraft { $0.subject = "Rotate keys" }
            await settle { list.debugRows()[1].contains("Rotate keys") }
            #expect(list.debugRows()[1] == "row* /draft | Draft, Rotate keys, to infra")

            store.discardDraft()
            await settle { rowIDs(list).count == 1 }
            #expect(rowIDs(list) == ["row  a"], "c isn't in the Inbox, so nothing is selected")
            #expect(store.newDraft == nil && !store.isShowingDraft)
        }
    }

    @Test func newMessageIsAnEmptyThreadWithItsReplyBox() async {
        await withStore { store in
            let thread = ThreadViewController(store: store)
            let window = show(thread)
            defer { window.close() }
            #expect(thread.paneMode == .reply)

            thread.newMessage(to: nil)
            #expect(store.isShowingDraft)
            #expect(state(thread, "mode") == "newThread to=\"\" items=[\"infra\", \"website\"]")
            #expect(state(thread, "draftHeader") == "subject=\"\" placeholder=Subject info=\"New thread\" "
                + "noMessages=shown web=hidden")
            #expect(state(thread, "buttons") == "send=Send(off) close=Discard(on) cancel=hidden")
            #expect(!thread.canCloseThread)

            store.editDraft { $0.to = "infra" }
            thread.replyView.string = "Hello"
            #expect(thread.canSendReply)
            store.editDraft { $0.to = "user" }
            #expect(!thread.canSendReply, "not to myself")
            store.editDraft { $0.to = "infra" }

            // Selecting a thread keeps the draft and its body.
            store.select(threadID: "a")
            await settle { state(thread, "mode") == "reply to=infra" }
            #expect(store.newDraft?.body == "Hello")
            #expect(state(thread, "draftHeader") == "hidden")
            store.showDraft()
            await settle { state(thread, "draftHeader") != "hidden" }
            #expect(state(thread, "mode").hasPrefix("newThread"))
            #expect(thread.replyView.string == "Hello")
            #expect(state(thread, "draftHeader").contains("info=\"New thread to infra\""))

            // Something was typed: Discard asks (in a sheet), so the draft stays.
            thread.discardDraft()
            #expect(store.newDraft != nil)
            if let sheet = window.attachedSheet { window.endSheet(sheet) }
            thread.replyView.string = ""
            store.editDraft { $0.to = "" }
            thread.discardDraft()
            #expect(store.newDraft == nil, "nothing typed: gone at once")
            let draftSelected = store.selectedThreadID
            #expect(draftSelected == "a", "back to the thread shown before")
        }
    }

    @Test func forwardOnAnOpenThreadHandsItOnInPlace() async {
        await withStore { store in
            let thread = ThreadViewController(store: store)
            let window = show(thread)
            defer { window.close() }
            store.select(threadID: "a")
            await settle { state(thread, "mode") == "reply to=infra" }
            #expect(state(thread, "buttons") == "send=Reply(off) close=Close Thread(on) cancel=hidden")

            thread.forward(threadID: "a")
            #expect(thread.paneMode == .forward)
            #expect(state(thread, "mode") == "forward to=\"\" items=[\"infra\", \"website\"]")
            #expect(state(thread, "buttons") == "send=Forward(off) close=hidden cancel=Cancel(on)")
            #expect(store.newDraft == nil, "no new thread for an open one")
            thread.replyView.string = "Over to you"
            #expect(!thread.canSendReply, "no recipient yet")

            thread.leaveForwardMode()
            #expect(state(thread, "mode") == "reply to=infra")
            #expect(thread.replyView.string == "Over to you", "the text stays")
            #expect(thread.canSendReply)

            // Forward from the list on another open thread selects it first.
            thread.forward(threadID: "b")
            #expect(store.selectedThreadID == "b")
            #expect(thread.paneMode == .forward)
        }
    }

    @Test func forwardOnAClosedThreadStartsANewOne() async {
        await withStore { store in
            let thread = ThreadViewController(store: store)
            let window = show(thread)
            defer { window.close() }
            store.select(threadID: "c")
            thread.forward(threadID: "c", message: "m2.md")
            #expect(store.isShowingDraft)
            #expect(store.newDraft?.forward == ForwardSource(threadID: "c", message: "m2.md"))
            #expect(store.newDraft?.subject == "Fwd: Old")
            #expect(thread.paneMode == .newThread)
            #expect(state(thread, "draftHeader") == "subject=\"Fwd: Old\" placeholder=Subject "
                + "info=\"Forwarding a message from #3\" noMessages=hidden web=shown")
            #expect(state(thread, "buttons") == "send=Forward(off) close=Discard(on) cancel=hidden")
            store.editDraft { $0.to = "docs" }
            #expect(thread.canSendReply, "a forward's note is optional")

            // A recipient was typed: another forward asks (in a sheet) first.
            thread.forward(threadID: "c")
            #expect(store.newDraft?.forward?.message == "m2.md", "the sheet asks before replacing typed work")
            if let sheet = window.attachedSheet { window.endSheet(sheet) }
            store.editDraft { $0.to = "" }
            thread.forward(threadID: "c")
            #expect(store.newDraft?.forward == ForwardSource(threadID: "c"))
            #expect(state(thread, "draftHeader").contains("info=\"Forwarding #3, its one message, after your note\""))
        }
    }

    @Test func aParticipantsMenuStartsANewMessageToIt() async throws {
        try await withStore { store in
            let sidebar = SidebarViewController(store: store)
            let thread = ThreadViewController(store: store)
            sidebar.onNewMessage = { thread.newMessage(to: $0) }
            let window = show(thread)
            defer { window.close() }
            let items = sidebar.menuItems(forParticipant: "infra")
            #expect(items.map(\.title) == ["New Message to infra", "", "Copy Name"])
            let compose = try #require(items.first)
            #expect(NSApp.sendAction(try #require(compose.action), to: compose.target, from: compose))
            #expect(store.isShowingDraft)
            #expect(store.newDraft?.to == "infra")
            #expect(state(thread, "mode") == "newThread to=\"infra\" items=[\"infra\", \"website\"]")
            #expect(state(thread, "draftHeader").contains("info=\"New thread to infra\""))

            // My own row: a new message, to nobody yet.
            store.discardDraft()
            let mine = try #require(sidebar.menuItems(forParticipant: "user").first)
            #expect(mine.title == "New Message")
            #expect(NSApp.sendAction(try #require(mine.action), to: mine.target, from: mine))
            #expect(store.newDraft?.to == "")
        }
    }

    @Test func theReplyBoxIsInTheBodiesTypeAndFollowsTheZoom() async {
        await withStore { store in
            let thread = ThreadViewController(store: store)
            let window = show(thread)
            defer { window.close() }
            thread.setTextZoom(1)
            let system = NSFont.systemFont(ofSize: 17).familyName ?? "?"
            #expect(thread.replyView.font == NSFont.systemFont(ofSize: 17), "thread.css: body at 17px")
            #expect(state(thread, "replyFont").hasPrefix("\(system) 17.0 lineSpacing="))
            #expect(state(thread, "replyFont").hasSuffix("(typing)"))
            let spacing = (thread.replyView.defaultParagraphStyle?.lineSpacing ?? 0)
            #expect(spacing > 4 && spacing < 10, "lines 1.6 × 17pt apart")

            thread.replyView.string = "Hello\nthere"
            thread.makeTextBigger()
            #expect(thread.textZoom == 1.1)
            thread.setTextZoom(1.5)
            #expect(state(thread, "replyFont").hasPrefix("\(system) 25.5 "), "all of the text, scaled with the page")
            let style = thread.replyView.textStorage?.attribute(.paragraphStyle, at: 0, effectiveRange: nil)
            let bigger = (style as? NSParagraphStyle)?.lineSpacing ?? 0
            #expect(bigger > spacing * 1.3 && bigger < spacing * 1.7)
            thread.replyView.setSelectedRange(NSRange(location: 11, length: 0))
            thread.replyView.insertText("!", replacementRange: thread.replyView.selectedRange())
            #expect(!state(thread, "replyFont").contains("last"), "typed text too")

            // Another thread's text, set by the selection, takes the zoom too.
            thread.replyView.string = "Other"
            #expect(state(thread, "replyFont").hasPrefix("\(system) 25.5 "))
            thread.makeTextActualSize()
            #expect(thread.replyView.font?.pointSize == 17)
        }
    }

    /// One line of the thread view's debug state, without its label.
    func state(_ thread: ThreadViewController, _ key: String) -> String {
        let prefix = "thread.\(key): "
        return thread.debugState().first { $0.hasPrefix(prefix) }.map { String($0.dropFirst(prefix.count)) } ?? "?"
    }
}
