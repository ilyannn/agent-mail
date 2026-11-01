// The menu bar extra: a tray symbol with the unread count, opening a menu
// (not a popover) that is rebuilt each time it opens.
import AgentMailKit
import AppKit
import Observation

final class StatusItemController: NSObject, NSMenuDelegate {
    static let inboxLimit = 8

    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let store: MailStore
    private weak var actions: AppDelegate?
    private var observation: Task<Void, Never>?

    init(store: MailStore, actions: AppDelegate) {
        self.store = store
        self.actions = actions
        super.init()
        item.autosaveName = "AgentMailStatusItem"
        item.button?.imagePosition = .imageLeading
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        updateButton()
        let changes = Observations { [store] in (store.index, store.refreshError) }
        observation = Task { [weak self] in
            for await _ in changes { self?.updateButton() }
        }
    }

    private func updateButton() {
        guard let button = item.button else { return }
        let unread = store.index.unreadInInbox
        let waiting = store.index.inbox.count
        let failing = store.refreshError != nil
        let symbol = failing ? "exclamationmark.triangle" : (waiting > 0 ? "tray.full" : "tray")
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        image?.isTemplate = true
        button.image = image
        button.title = unread > 0 ? "\(unread)" : ""
        var label = "Agent Mail"
        if failing {
            label += ", can’t read mail"
        } else if unread > 0 {
            label += ", \(unread) unread"
        } else if waiting > 0 {
            label += ", \(waiting) waiting on you"
        }
        button.setAccessibilityLabel(label)
        button.toolTip = label
        // Shown only while the app has a Dock icon (Settings › General).
        NSApp.dockTile.badgeLabel = unread > 0 ? "\(unread)" : nil
    }

    // MARK: NSMenuDelegate

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let index = store.index

        if let error = store.refreshError {
            let i = NSMenuItem(title: error.errorDescription ?? "Can’t read mail", action: #selector(AppDelegate.showSettings(_:)),
                               keyEquivalent: "")
            i.target = actions
            i.image = NSImage(systemSymbolName: "exclamationmark.triangle", accessibilityDescription: "Error")
            menu.addItem(i)
            menu.addItem(.separator())
        }

        let inbox = index.inbox
        menu.addItem(NSMenuItem.sectionHeader(title: inbox.isEmpty ? "Nothing waiting on you" : "Inbox"))
        for t in inbox.prefix(Self.inboxLimit) {
            let who = t.lastFrom ?? t.from ?? "?"
            let i = NSMenuItem(title: "\(who): \(t.subject)", action: #selector(openThread(_:)), keyEquivalent: "")
            i.target = self
            i.representedObject = t.id
            if t.unread > 0 {
                i.image = NSImage(systemSymbolName: "circle.fill", accessibilityDescription: "Unread")?
                    .withSymbolConfiguration(.init(pointSize: 7, weight: .regular).applying(.init(paletteColors: [.controlAccentColor])))
            }
            i.toolTip = "#\(t.number.map(String.init) ?? "?") · \(Formatting.age(of: t.updated, now: .now)) ago"
            menu.addItem(i)
        }
        if inbox.count > Self.inboxLimit {
            let more = NSMenuItem(title: "\(inbox.count - Self.inboxLimit) More…", action: #selector(openMailbox(_:)),
                                  keyEquivalent: "")
            more.target = self
            more.representedObject = "inbox"
            menu.addItem(more)
        }
        menu.addItem(.separator())

        let undelivered = NSMenuItem(title: "Undelivered (\(index.undelivered.count))", action: #selector(openMailbox(_:)),
                                     keyEquivalent: "")
        undelivered.target = self
        undelivered.representedObject = "undelivered"
        menu.addItem(undelivered)

        let duty = NSMenuItem(title: "On Duty", action: nil, keyEquivalent: "")
        let dutyMenu = NSMenu(title: "On Duty")
        if index.onDuty.isEmpty {
            let none = NSMenuItem(title: "Nobody on duty", action: nil, keyEquivalent: "")
            none.isEnabled = false
            dutyMenu.addItem(none)
        }
        for agent in index.onDuty {
            let i = NSMenuItem(title: agent.name, action: #selector(openMailbox(_:)), keyEquivalent: "")
            i.target = self
            i.representedObject = "agent:\(agent.name)"
            i.image = NSImage(systemSymbolName: "circle.fill", accessibilityDescription: "On duty")?
                .withSymbolConfiguration(.init(pointSize: 7, weight: .regular).applying(.init(paletteColors: [.systemGreen])))
            dutyMenu.addItem(i)
        }
        duty.submenu = dutyMenu
        menu.addItem(duty)
        menu.addItem(.separator())

        menu.addItem(actionItem("New Message…", #selector(AppDelegate.newMessage(_:)), "n"))
        menu.addItem(actionItem("Open Agent Mail", #selector(AppDelegate.showMainWindow(_:)), "0"))
        menu.addItem(.separator())
        menu.addItem(actionItem("Settings…", #selector(AppDelegate.showSettings(_:)), ","))
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "Quit Agent Mail", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.addItem(quit)
    }

    #if DEBUG
    var debugTitle: String { item.button?.title ?? "" }

    func debugMenuDump() -> String {
        let menu = NSMenu()
        menuNeedsUpdate(menu)
        func lines(_ m: NSMenu, _ depth: Int) -> [String] {
            m.items.flatMap { i -> [String] in
                let pad = String(repeating: "  ", count: depth)
                let shift = i.keyEquivalentModifierMask.contains(.shift) ? "⇧" : ""
                let key = i.keyEquivalent.isEmpty ? "" : " [\(shift)⌘\(i.keyEquivalent)]"
                let me = i.isSeparatorItem ? pad + "---" : pad + i.title + key + (i.isEnabled ? "" : " (disabled)")
                return [me] + (i.submenu.map { lines($0, depth + 1) } ?? [])
            }
        }
        return lines(menu, 0).joined(separator: "\n") + "\n"
    }
    #endif

    private func actionItem(_ title: String, _ action: Selector, _ key: String) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
        i.target = actions
        return i
    }

    @objc private func openThread(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        actions?.openThread(id)
    }

    @objc private func openMailbox(_ sender: NSMenuItem) {
        guard let raw = sender.representedObject as? String else { return }
        let mailbox: Mailbox = switch raw {
        case "undelivered": .undelivered
        case _ where raw.hasPrefix("agent:"): .agent(String(raw.dropFirst(6)))
        default: .inbox
        }
        actions?.open(mailbox: mailbox)
    }
}
