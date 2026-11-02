// The menu bar, with Mail's shortcuts where Mail has the command: New ⌘N,
// Reply ⌘R, Send ⇧⌘D, Close Thread ⌃⌘A (Mail's Archive), Get New Mail ⇧⌘N;
// mailboxes ⌘1–3 and the sidebar ⌃⌘S. Text size has Safari's: Actual Size
// ⌘0, Make Text Bigger ⌘+ (and ⌘=), Make Text Smaller ⌘−, so the main
// window is ⌥⌘0. ⌘↩ also sends from the reply box (ThreadViewController). Every command is here, so it can be
// rebound in System Settings › Keyboard › Keyboard Shortcuts. Actions go
// down the responder chain (nil target).
import AgentMailKit
import AppKit

@objc protocol MailActions {
    func newMessage(_ sender: Any?)
    func showMainWindow(_ sender: Any?)
    func showSettings(_ sender: Any?)
    func refresh(_ sender: Any?)
    func showHelp(_ sender: Any?)
    func showInbox(_ sender: Any?)
    func showUndelivered(_ sender: Any?)
    func showAllMail(_ sender: Any?)
    /// Sort By: the sender is a menu item whose tag is a ThreadOrder's menuTag.
    func setThreadOrder(_ sender: Any?)
    func replyToThread(_ sender: Any?)
    func sendReply(_ sender: Any?)
    func closeThread(_ sender: Any?)
    func copyThreadID(_ sender: Any?)
    func revealThreadInFinder(_ sender: Any?)
    func increaseTextSize(_ sender: Any?)
    func decreaseTextSize(_ sender: Any?)
    func resetTextSize(_ sender: Any?)
}

enum MainMenu {
    static func build() -> NSMenu {
        let bar = NSMenu()
        bar.addItem(submenu(appMenu()))
        bar.addItem(submenu(fileMenu()))
        bar.addItem(submenu(editMenu()))
        bar.addItem(submenu(viewMenu()))
        bar.addItem(submenu(mailboxMenu()))
        bar.addItem(submenu(messageMenu()))
        let window = windowMenu()
        bar.addItem(submenu(window))
        NSApp.windowsMenu = window
        let help = helpMenu()
        bar.addItem(submenu(help))
        NSApp.helpMenu = help
        return bar
    }

    private static func submenu(_ menu: NSMenu) -> NSMenuItem {
        let item = NSMenuItem(title: menu.title, action: nil, keyEquivalent: "")
        item.submenu = menu
        return item
    }

    private static func item(_ title: String, _ action: Selector?, _ key: String = "",
                             _ modifiers: NSEvent.ModifierFlags = .command, tag: Int = 0) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
        i.keyEquivalentModifierMask = modifiers
        i.tag = tag
        return i
    }

    private static func appMenu() -> NSMenu {
        let name = "Agent Mail"
        let m = NSMenu(title: name)
        m.addItem(item("About \(name)", #selector(NSApplication.orderFrontStandardAboutPanel(_:))))
        m.addItem(.separator())
        m.addItem(item("Settings…", #selector(MailActions.showSettings(_:)), ","))
        m.addItem(.separator())
        let services = NSMenu(title: "Services")
        let servicesItem = item("Services", nil)
        servicesItem.submenu = services
        NSApp.servicesMenu = services
        m.addItem(servicesItem)
        m.addItem(.separator())
        m.addItem(item("Hide \(name)", #selector(NSApplication.hide(_:)), "h"))
        m.addItem(item("Hide Others", #selector(NSApplication.hideOtherApplications(_:)), "h", [.command, .option]))
        m.addItem(item("Show All", #selector(NSApplication.unhideAllApplications(_:))))
        m.addItem(.separator())
        m.addItem(item("Quit \(name)", #selector(NSApplication.terminate(_:)), "q"))
        return m
    }

    private static func fileMenu() -> NSMenu {
        let m = NSMenu(title: "File")
        m.addItem(item("New Message", #selector(MailActions.newMessage(_:)), "n"))
        m.addItem(.separator())
        m.addItem(item("Close Window", #selector(NSWindow.performClose(_:)), "w"))
        return m
    }

    private static func editMenu() -> NSMenu {
        let m = NSMenu(title: "Edit")
        m.addItem(item("Undo", Selector(("undo:")), "z"))
        m.addItem(item("Redo", Selector(("redo:")), "z", [.command, .shift]))
        m.addItem(.separator())
        m.addItem(item("Cut", #selector(NSText.cut(_:)), "x"))
        m.addItem(item("Copy", #selector(NSText.copy(_:)), "c"))
        m.addItem(item("Paste", #selector(NSText.paste(_:)), "v"))
        m.addItem(item("Paste and Match Style", #selector(NSTextView.pasteAsPlainText(_:)), "v", [.command, .option, .shift]))
        m.addItem(item("Delete", #selector(NSText.delete(_:))))
        m.addItem(item("Select All", #selector(NSText.selectAll(_:)), "a"))
        m.addItem(.separator())

        let find = NSMenu(title: "Find")
        let finder = #selector(NSResponder.performTextFinderAction(_:))
        find.addItem(item("Find…", finder, "f", tag: NSTextFinder.Action.showFindInterface.rawValue))
        find.addItem(item("Find and Replace…", finder, "f", [.command, .option],
                          tag: NSTextFinder.Action.showReplaceInterface.rawValue))
        find.addItem(item("Find Next", finder, "g", tag: NSTextFinder.Action.nextMatch.rawValue))
        find.addItem(item("Find Previous", finder, "g", [.command, .shift], tag: NSTextFinder.Action.previousMatch.rawValue))
        find.addItem(item("Use Selection for Find", finder, "e", tag: NSTextFinder.Action.setSearchString.rawValue))
        find.addItem(item("Jump to Selection", #selector(NSResponder.centerSelectionInVisibleArea(_:)), "j"))
        m.addItem(withSubmenu(find))

        let spelling = NSMenu(title: "Spelling and Grammar")
        spelling.addItem(item("Show Spelling and Grammar", #selector(NSText.showGuessPanel(_:)), ":"))
        spelling.addItem(item("Check Document Now", #selector(NSText.checkSpelling(_:)), ";"))
        spelling.addItem(.separator())
        spelling.addItem(item("Check Spelling While Typing", #selector(NSTextView.toggleContinuousSpellChecking(_:))))
        spelling.addItem(item("Check Grammar With Spelling", #selector(NSTextView.toggleGrammarChecking(_:))))
        spelling.addItem(item("Correct Spelling Automatically", #selector(NSTextView.toggleAutomaticSpellingCorrection(_:))))
        m.addItem(withSubmenu(spelling))

        let subs = NSMenu(title: "Substitutions")
        subs.addItem(item("Show Substitutions", #selector(NSTextView.orderFrontSubstitutionsPanel(_:))))
        subs.addItem(.separator())
        subs.addItem(item("Smart Copy/Paste", #selector(NSTextView.toggleSmartInsertDelete(_:))))
        subs.addItem(item("Smart Quotes", #selector(NSTextView.toggleAutomaticQuoteSubstitution(_:))))
        subs.addItem(item("Smart Dashes", #selector(NSTextView.toggleAutomaticDashSubstitution(_:))))
        subs.addItem(item("Smart Links", #selector(NSTextView.toggleAutomaticLinkDetection(_:))))
        subs.addItem(item("Data Detectors", #selector(NSTextView.toggleAutomaticDataDetection(_:))))
        subs.addItem(item("Text Replacement", #selector(NSTextView.toggleAutomaticTextReplacement(_:))))
        m.addItem(withSubmenu(subs))

        let transforms = NSMenu(title: "Transformations")
        transforms.addItem(item("Make Upper Case", #selector(NSResponder.uppercaseWord(_:))))
        transforms.addItem(item("Make Lower Case", #selector(NSResponder.lowercaseWord(_:))))
        transforms.addItem(item("Capitalize", #selector(NSResponder.capitalizeWord(_:))))
        m.addItem(withSubmenu(transforms))

        let speech = NSMenu(title: "Speech")
        speech.addItem(item("Start Speaking", #selector(NSTextView.startSpeaking(_:))))
        speech.addItem(item("Stop Speaking", #selector(NSTextView.stopSpeaking(_:))))
        m.addItem(withSubmenu(speech))
        return m
    }

    private static func withSubmenu(_ menu: NSMenu) -> NSMenuItem {
        let i = NSMenuItem(title: menu.title, action: nil, keyEquivalent: "")
        i.submenu = menu
        return i
    }

    private static func viewMenu() -> NSMenu {
        let m = NSMenu(title: "View")
        m.addItem(item("Show Sidebar", #selector(NSSplitViewController.toggleSidebar(_:)), "s", [.command, .control]))
        m.addItem(.separator())
        m.addItem(item("Actual Size", #selector(MailActions.resetTextSize(_:)), "0"))
        m.addItem(item("Make Text Bigger", #selector(MailActions.increaseTextSize(_:)), "+"))
        // ⌘= without Shift, as in Safari: a hidden twin that keeps its shortcut.
        let bigger = item("Make Text Bigger", #selector(MailActions.increaseTextSize(_:)), "=")
        bigger.isHidden = true
        bigger.allowsKeyEquivalentWhenHidden = true
        m.addItem(bigger)
        m.addItem(item("Make Text Smaller", #selector(MailActions.decreaseTextSize(_:)), "-"))
        m.addItem(.separator())
        m.addItem(item("Inbox", #selector(MailActions.showInbox(_:)), "1"))
        m.addItem(item("Undelivered", #selector(MailActions.showUndelivered(_:)), "2"))
        m.addItem(item("All Mail", #selector(MailActions.showAllMail(_:)), "3"))
        m.addItem(.separator())
        m.addItem(withSubmenu(sortMenu()))
        m.addItem(.separator())
        m.addItem(item("Enter Full Screen", #selector(NSWindow.toggleFullScreen(_:)), "f", [.command, .control]))
        return m
    }

    /// View › Sort By, and the toolbar's sort menu: one item per order, the
    /// current one checked by validateMenuItem.
    static func sortMenu() -> NSMenu {
        let m = NSMenu(title: "Sort By")
        for order in ThreadOrder.allCases {
            m.addItem(item(order.title, #selector(MailActions.setThreadOrder(_:)), tag: order.menuTag))
        }
        return m
    }

    private static func mailboxMenu() -> NSMenu {
        let m = NSMenu(title: "Mailbox")
        m.addItem(item("Get New Mail", #selector(MailActions.refresh(_:)), "n", [.command, .shift]))
        return m
    }

    private static func messageMenu() -> NSMenu {
        let m = NSMenu(title: "Message")
        m.addItem(item("Send", #selector(MailActions.sendReply(_:)), "d", [.command, .shift]))
        m.addItem(.separator())
        m.addItem(item("Reply", #selector(MailActions.replyToThread(_:)), "r"))
        m.addItem(item("Close Thread", #selector(MailActions.closeThread(_:)), "a", [.command, .control]))
        m.addItem(.separator())
        m.addItem(item("Copy Thread ID", #selector(MailActions.copyThreadID(_:))))
        m.addItem(item("Show in Finder", #selector(MailActions.revealThreadInFinder(_:))))
        return m
    }

    private static func windowMenu() -> NSMenu {
        let m = NSMenu(title: "Window")
        m.addItem(item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"))
        m.addItem(item("Zoom", #selector(NSWindow.performZoom(_:))))
        m.addItem(.separator())
        m.addItem(item("Agent Mail", #selector(MailActions.showMainWindow(_:)), "0", [.command, .option]))
        m.addItem(.separator())
        m.addItem(item("Bring All to Front", #selector(NSApplication.arrangeInFront(_:))))
        return m
    }

    private static func helpMenu() -> NSMenu {
        let m = NSMenu(title: "Help")
        m.addItem(item("Agent Mail Help", #selector(MailActions.showHelp(_:)), "?"))
        return m
    }
}

extension ThreadOrder {
    /// The tag of this order's Sort By item.
    var menuTag: Int { Self.allCases.firstIndex(of: self) ?? 0 }

    /// The order a Sort By item stands for.
    init?(menuItem sender: Any?) {
        guard let tag = (sender as? NSMenuItem)?.tag, Self.allCases.indices.contains(tag) else { return nil }
        self = Self.allCases[tag]
    }
}
