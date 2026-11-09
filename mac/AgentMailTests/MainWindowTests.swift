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

    /// The same threads with an agent directory: docs on call in a folder
    /// Claude Code hasn't trusted, infra on call, notes off call; website
    /// only appears in mail.
    static let directoryStatus = MailStatus(
        now: status.now, me: "user", threads: status.threads, oncall: [OnCallRepo(repo: "docs"), OnCallRepo(repo: "infra")],
        serve: ServeInfo(alive: true, pid: 1), agents: [
            DirectoryEntry(name: "docs", dir: "/x/docs", summary: "Owns the docs.", trusted: false),
            DirectoryEntry(name: "infra", dir: "/x/infra", summary: "Owns the servers.", trusted: true),
            DirectoryEntry(name: "notes", dir: "/x/notes", onCall: false, summary: "Keeps notes."),
        ])

    @Test func fileMenuAddsAFolder() throws {
        let menu = MainMenu.fileMenu()
        #expect(menu.items.map(\.title) == ["New Message", "", "Add Folder…", "", "Close Window"])
        let add = try #require(menu.items.first { $0.title == "Add Folder…" })
        #expect(add.action == #selector(MailActions.addFolder(_:)))
    }

    @Test func aParticipantsDotAndNameSayWhoPicksItsMailUp() {
        func icon(_ who: AgentInfo) -> String? { SidebarViewController.icon(for: who)?.accessibilityDescription }
        let onCall = AgentInfo(name: "infra", entry: DirectoryEntry(name: "infra"), directoryKnown: true, serveRunning: true)
        #expect(icon(onCall) == "On call")
        #expect(!SidebarViewController.isDimmed(onCall))
        var noServe = onCall
        noServe.serveRunning = false
        #expect(icon(noServe) == "On call, serve isn’t running", "a green ring: nothing delivers until serve runs")
        #expect(!SidebarViewController.isDimmed(noServe))
        let off = AgentInfo(name: "notes", entry: DirectoryEntry(name: "notes", onCall: false), directoryKnown: true)
        #expect(icon(off) == "Off call")
        #expect(SidebarViewController.isDimmed(off))
        #expect(icon(AgentInfo(name: "x", onDuty: true)) == "On duty")
        #expect(!SidebarViewController.isDimmed(AgentInfo(name: "x", onDuty: true)))
        #expect(!SidebarViewController.isDimmed(AgentInfo(name: "user", isMe: true)))
    }

    @Test func theSidebarListsTheDirectory() async throws {
        try await withStore { store in
            store.debugShow(Self.directoryStatus)
            let sidebar = SidebarViewController(store: store)
            let window = show(sidebar, size: NSSize(width: 260, height: 500))
            defer { window.close() }
            let rows = sidebar.debugRows().filter { $0.hasPrefix("sidebar") }.dropFirst(5)
            #expect(rows.map { $0.components(separatedBy: " | ")[0] } == [
                "sidebar  user (you), 1 thread waiting on you", "sidebar  docs, on call, 1 warning",
                "sidebar  infra, on call, 1 thread waiting on infra",
                "sidebar  notes, off call", "sidebar  website, not on duty",
            ], "notes has no mail, but is in the directory")
            let docs = try #require(rows.first { $0.hasPrefix("sidebar  docs") })
            #expect(docs.hasSuffix(" | icon: On call | warn: Claude Code hasn’t trusted /x/docs yet, so serve can’t "
                + "start a session there: run `claude` in it once and accept the question."))
            #expect(docs.contains("| tip: docs: on call — serve starts a session for each thread / Owns the docs. / "))
            #expect(rows.first { $0.hasPrefix("sidebar  notes") }?.hasSuffix(" | dimmed | icon: Off call") == true)
            #expect(rows.first { $0.hasPrefix("sidebar  website") }
                == "sidebar  website, not on duty | tip: website: not in the agent directory / Nothing waiting on website"
                + " | dimmed | icon: Not on duty")
            #expect(!rows.contains { $0.hasPrefix("sidebar  infra") && $0.contains("| dimmed") }, "on call: not dimmed")
            #expect(!rows.contains { $0.hasPrefix("sidebar  user") && $0.contains("| dimmed") }, "never the user")

            #expect(sidebar.debugMenu(for: .agent("docs")) == ["New Message to docs", "On Call✓", "---", "Copy Name",
                                                               "---", "Add Folder…"])
            #expect(sidebar.debugMenu(for: .agent("notes")).prefix(2) == ["New Message to notes", "On Call"])
            #expect(sidebar.debugMenu(for: .agent("website")) == ["New Message to website", "---", "Copy Name", "---",
                                                                  "Add Folder…"], "not in the directory: no On Call")
            #expect(sidebar.debugMenu(for: .agent("user")) == ["New Message", "---", "Copy Name", "---", "Add Folder…"])
            #expect(sidebar.debugMenu(for: .inbox) == ["Add Folder…"])
            #expect(sidebar.debugMenu(for: nil) == ["Add Folder…"])

            // Off call through the CLI, which isn't there: the error shows on the window.
            #expect(sidebar.debugPickMenuItem("On Call", for: .agent("docs")))
            await settle { window.attachedSheet != nil }
            #expect(window.attachedSheet != nil)
            if let sheet = window.attachedSheet { window.endSheet(sheet) }
        }
    }

    @Test func anAgentsMailboxSaysWhatNeedsTheUser() async {
        await withStore { store in
            var st = Self.directoryStatus
            st.oncall = [OnCallRepo(repo: "docs"),
                         OnCallRepo(repo: "infra", cannotLand: "uncommitted changes",
                                    unlanded: [UnlandedSession(id: "ab12", name: "📫 infra #0", branch: "w", commits: 2)])]
            store.debugShow(st)
            let list = ThreadListViewController(store: store)
            let window = show(list, size: NSSize(width: 320, height: 400))
            defer { window.close() }
            #expect(list.debugRows()[1].hasPrefix("row  a | "), "the Inbox has none")

            store.mailbox = .agent("infra")
            await settle { list.debugRows()[1].hasPrefix("notices") }
            #expect(list.debugRows()[1] == "notices: Serve can’t bring the main checkout up to date with what its "
                + "sessions pushed: uncommitted changes. / Unlanded work of “📫 infra #0”: branch w, 2 commits; serve "
                + "keeps the session until it is merged.")
            #expect(rowIDs(list) == ["row  a", "row  b", "row  c"])

            store.mailbox = .agent("docs")
            await settle { list.debugRows()[1].contains("trusted") }
            #expect(list.debugRows()[1].hasPrefix("notices: Claude Code hasn’t trusted /x/docs yet"))
            store.mailbox = .agent("notes")
            await settle { !list.debugRows()[1].hasPrefix("notices") }
            #expect(list.debugRows()[1] == "emptyLabel: No threads with notes", "nothing wrong with notes")
        }
    }

    @Test func toOffersTheDirectoryAndSaysWhatIsWrong() async {
        await withStore { store in
            store.debugShow(Self.directoryStatus)
            let thread = ThreadViewController(store: store)
            let window = show(thread)
            defer { window.close() }
            thread.newMessage(to: nil)
            #expect(state(thread, "mode") == "newThread to=\"\" items=[\"docs\", \"infra\", \"notes\"]",
                    "the directory, not website from older mail")
            #expect(thread.numberOfItems(in: NSComboBox()) == 3)
            #expect(thread.comboBox(NSComboBox(), objectValueForItemAt: 1) as? String == "infra — Owns the servers.")
            #expect(thread.comboBox(NSComboBox(), completedString: "IN") == "infra", "a name, never its summary")
            #expect(thread.comboBox(NSComboBox(), indexOfItemWithStringValue: "notes") == 2)

            thread.replyView.string = "Hello"
            thread.debugCommitTo("infr")
            await settle { state(thread, "status") != "" }
            #expect(state(thread, "status") == "No agent named infr in the directory: did you mean infra?")
            #expect(!thread.canSendReply, "the CLI would refuse it")
            thread.debugCommitTo("infra — Owns the servers.")
            #expect(store.newDraft?.to == "infra", "a picked title leaves the name")
            await settle { state(thread, "status") == "Owns the servers." }
            #expect(state(thread, "mode").hasPrefix("newThread to=\"infra\""))
            #expect(state(thread, "status") == "Owns the servers.")
            #expect(thread.canSendReply)
            thread.debugCommitTo("notes")
            await settle { state(thread, "status") != "Owns the servers." }
            #expect(state(thread, "status") == "Keeps notes. (off call)")

            // A reply to a name from older mail: the CLI refuses it. Forward
            // mode offers the directory too.
            store.discardDraft()
            store.select(threadID: "c")
            await settle { state(thread, "mode") == "reply to=website" }
            thread.replyView.string = "Done?"
            #expect(!thread.canSendReply)
            store.select(threadID: "b")
            thread.forward(threadID: "b")
            #expect(state(thread, "mode") == "forward to=\"\" items=[\"docs\", \"infra\", \"notes\"]")
            thread.debugCommitTo("website")
            #expect(state(thread, "status").hasPrefix("No agent named website in the directory"))
            thread.debugCommitTo("docs")
            #expect(state(thread, "status") == "They see the whole thread, and it becomes their turn")
        }
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

    @Test func theDraftIsARowAtTheTopOfTheMailboxesItsThreadWillBeIn() async {
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

            // It will wait on infra: not in the Inbox, but in infra's mailbox.
            store.mailbox = .inbox
            await settle { rowIDs(list).count == 1 }
            #expect(rowIDs(list) == ["row  a"])
            store.mailbox = .agent("infra")
            await settle { rowIDs(list).count == 4 }
            #expect(rowIDs(list) == ["row  /draft", "row  a", "row  b", "row  c"])

            // Not in website's mailbox until it is to website.
            store.mailbox = .agent("website")
            await settle { rowIDs(list) == ["row  c"] }
            #expect(rowIDs(list) == ["row  c"])
            store.editDraft { $0.to = "website" }
            await settle { rowIDs(list).count == 2 }
            #expect(rowIDs(list) == ["row  /draft", "row  c"])
            #expect(list.debugRows()[1] == "row  /draft | Draft, New Message, to website")

            // My own mailbox holds it, To or not, as the filter allows.
            store.mailbox = .agent("user")
            store.participantFilter = .waiting
            await settle { rowIDs(list) == ["row  a"] }
            #expect(rowIDs(list) == ["row  a"], "it won't wait on me")
            store.participantFilter = .sent
            await settle { rowIDs(list).count == 2 }
            #expect(rowIDs(list) == ["row  /draft", "row  b"], "I start it")
            store.participantFilter = .all
            store.editDraft { $0.to = "" }
            await settle { rowIDs(list).count == 3 }
            #expect(rowIDs(list) == ["row  /draft", "row  a", "row  b"])

            store.showDraft()
            store.editDraft { $0.subject = "Rotate keys" }
            await settle { list.debugRows()[1].contains("Rotate keys") }
            #expect(list.debugRows()[1] == "row* /draft | Draft, Rotate keys, Draft")

            store.discardDraft()
            await settle { rowIDs(list).count == 2 }
            #expect(rowIDs(list) == ["row  a", "row  b"], "nothing was selected before the draft")
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
