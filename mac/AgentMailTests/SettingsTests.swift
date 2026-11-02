// Settings, the store location and the Dock policy.
import AgentMailKit
import AppKit
import Foundation
import Testing
@testable import AgentMail

@MainActor @Suite struct SettingsTests {
    func withSettings(_ body: (AppSettings) throws -> Void) rethrows {
        let suite = "agent-mail-tests-\(UUID().uuidString)"
        defer { UserDefaults.standard.removePersistentDomain(forName: suite) }
        try body(AppSettings(defaults: UserDefaults(suiteName: suite)!))
    }

    @Test func defaults() {
        withSettings { s in
            #expect(s.dockPolicy == .whileWindowOpen)
            #expect(!s.notifyAgentTraffic)
            #expect(s.mainWindowWasOpen)
            #expect(s.cliPath == nil)
            #expect(s.mailbox == .inbox)
            #expect(s.threadOrder == .newestFirst)
        }
    }

    @Test func threadOrderRoundTrips() {
        withSettings { s in
            for order in ThreadOrder.allCases {
                s.threadOrder = order
                #expect(s.threadOrder == order)
            }
        }
    }

    @Test func sortMenuHasOneItemPerOrder() {
        let menu = MainMenu.sortMenu()
        #expect(menu.title == "Sort By")
        #expect(menu.items.map(\.title) == ["Newest First", "Open First"])
        #expect(menu.items.compactMap { ThreadOrder(menuItem: $0) } == ThreadOrder.allCases)
        #expect(menu.items.allSatisfy { $0.action == #selector(MailActions.setThreadOrder(_:)) })
        #expect(ThreadOrder(menuItem: nil) == nil)
    }

    @Test func listRowsSpeakTheirFlow() {
        let cell = ThreadCellView()
        let now = Date.now
        let back = ThreadSummary(id: "a", number: 7, subject: "Deploy", owner: "user", count: 2, updated: now, unread: 1,
                                 flow: ["user", "infra", "user"], listener: "user")
        cell.configure(back, me: "user", now: now)
        #expect(cell.accessibilityLabel() == "1 unread, user to infra, back to user, Deploy, 0 seconds ago, Your turn")
        #expect(cell.toolTip == "#7 a\n2 messages, waiting on user")
        let long = ThreadSummary(id: "b", number: 8, subject: "Long", owner: "c", status: "done", count: 5, updated: now,
                                 flow: ["a", "b", "a", "b", "c"])
        cell.configure(long, me: "user", now: now)
        #expect(cell.accessibilityLabel() == "a, then 2 others, back to b, then c, Long, 0 seconds ago, Closed")
        #expect(cell.toolTip == "#8 b\na → b → a → b → c\n5 messages, closed")
    }

    @Test func mailboxRoundTrips() {
        withSettings { s in
            for m: Mailbox in [.inbox, .undelivered, .allMail, .agent("infra")] {
                s.mailbox = m
                #expect(s.mailbox == m)
            }
            s.cliPath = ""
            #expect(s.cliPath == nil)
        }
    }

    @Test func textZoomIsKeptWithinItsLevels() {
        withSettings { s in
            #expect(s.textZoom == 1)
            s.textZoom = 1.5
            #expect(s.textZoom == 1.5)
            s.textZoom = 10
            #expect(s.textZoom == 3)
            s.textZoom = 0
            #expect(s.textZoom == 0.5)
        }
    }

    @Test func listRowsShowTheirStateAsIcons() {
        func state(_ t: ThreadSummary) -> ThreadCellView.RowState? { ThreadCellView.RowState.of(t, me: "user") }
        let mine = ThreadSummary(id: "a", subject: "s", owner: "user", listener: "user")
        #expect(state(mine)?.symbol == "arrowshape.turn.up.left.fill")
        #expect(state(mine)?.label == "Your turn")
        let lost = ThreadSummary(id: "b", subject: "s", owner: "docs", listener: "none")
        #expect(state(lost)?.symbol == "exclamationmark.circle")
        #expect(state(lost)?.detail == "Nobody is listening for docs")
        let done = ThreadSummary(id: "c", subject: "s", owner: "infra", status: "done")
        #expect(state(done)?.symbol == "checkmark.circle")
        let duty = ThreadSummary(id: "d", subject: "s", owner: "website", listener: "on-duty")
        #expect(state(duty)?.symbol == "circle.fill")
        #expect(state(duty)?.color == .systemGreen)
        #expect(state(ThreadSummary(id: "e", subject: "s", owner: "infra", listener: nil)) == nil)
    }

    @Test func storeLocationPrefersTheEnvironment() {
        withSettings { s in
            s.storePath = "/tmp/from-settings"
            #expect(StoreLocation.resolve(environment: ["AGENT_MAIL_DIR": "/tmp/from-env"], settings: s).url.path == "/tmp/from-env")
            let fromSettings = StoreLocation.resolve(environment: [:], settings: s)
            #expect(fromSettings.url.path == "/tmp/from-settings")
            #expect(fromSettings.source == .settings)
            s.storePath = nil
            #expect(StoreLocation.resolve(environment: ["XDG_DATA_HOME": "/tmp/xdg"], settings: s).url.path == "/tmp/xdg/agent-mail")
            let standard = StoreLocation.resolve(environment: [:], settings: s)
            #expect(standard.url.path.hasSuffix("/.local/share/agent-mail"))
            #expect(standard.source == .standard)
        }
    }

    @Test func dockPolicy() {
        withSettings { s in
            let dock = DockPolicyController(settings: s)
            s.dockPolicy = .always
            #expect(dock.desiredPolicy == .regular)
            s.dockPolicy = .never
            #expect(dock.desiredPolicy == .accessory)
            s.dockPolicy = .whileWindowOpen
            // The test host opens no windows.
            #expect(dock.desiredPolicy == (dock.hasOpenWindow ? .regular : .accessory))
        }
    }

    @Test func cliEnvironmentActsAsUserAgainstTheStore() {
        let env = CLIClient.environment(base: ["PATH": "/usr/bin", "AGENT_MAIL_AS": "infra"], store: URL(filePath: "/tmp/s"))
        #expect(env["AGENT_MAIL_AS"] == "user")
        #expect(env["AGENT_MAIL_DIR"] == "/tmp/s")
        #expect(env["PATH"] == "/usr/bin")
    }
}
