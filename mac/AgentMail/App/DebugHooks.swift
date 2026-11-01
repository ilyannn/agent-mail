// Debug builds only, and only with AGENT_MAIL_DEBUG_HOOKS=1: lets a script
// drive and inspect the running app, for coding agents that can't see the
// screen. The app listens for the distributed notification
// "dev.agent-mail.debug" whose object is a JSON command, e.g.
//   {"cmd": "dump", "out": "/tmp/state.txt"}
//   {"cmd": "snapshot", "out": "/tmp/window.png", "window": "General"}
//                                                   (+ .web.png for the thread)
//   {"cmd": "select", "arg": "<thread id>"}         (clicks the row)
//   {"cmd": "mailbox", "arg": "inbox|undelivered|allMail|agent:NAME"}
//   {"cmd": "key", "arg": "r", "mods": "cmd,shift"} (through the menus)
//   {"cmd": "type", "arg": "text"}                  (into the first responder)
//   {"cmd": "action", "arg": "replyToThread:"}      (down the responder chain)
//   {"cmd": "statusmenu", "out": "..."}             (the status item's menu)
//   {"cmd": "show" | "close" | "settings" | "compose" | "dock", "arg": ...}
//   {"cmd": "visible", "arg": "on"}                 (treat the thread as seen)
// mac/scripts/debug.bb sends them.
#if DEBUG
import AgentMailKit
import AppKit
import WebKit

final class DebugHooks: NSObject {
    private weak var app: AppDelegate?

    static func installIfRequested(app: AppDelegate) -> DebugHooks? {
        guard ProcessInfo.processInfo.environment["AGENT_MAIL_DEBUG_HOOKS"] == "1" else { return nil }
        let hooks = DebugHooks()
        hooks.app = app
        DistributedNotificationCenter.default().addObserver(hooks, selector: #selector(received(_:)),
                                                            name: .init("dev.agent-mail.debug"), object: nil,
                                                            suspensionBehavior: .deliverImmediately)
        Timing.note("debug hooks listening")
        return hooks
    }

    @objc private func received(_ note: Notification) {
        guard let json = note.object as? String, let data = json.data(using: .utf8),
              let cmd = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return }
        run(cmd)
    }

    private func run(_ cmd: [String: String]) {
        guard let app else { return }
        let arg = cmd["arg"] ?? ""
        let out = cmd["out"].map { URL(filePath: $0) }
        switch cmd["cmd"] {
        case "dump": write(dump(app), to: out)
        case "snapshot": snapshot(to: out, title: cmd["window"])
        case "select": app.debugMain?.debugSelect(arg)
        case "mailbox": app.debugMain?.debugSelectMailbox(arg)
        case "key":
            whenActive { [self] in
                key(arg, mods: cmd["mods"] ?? "")
                write("ok key\n", to: out)
            }
            return
        case "type":
            whenActive { [self] in
                let responder = NSApp.keyWindow?.firstResponder
                (responder as? NSTextInputClient)?.insertText(arg, replacementRange: NSRange(location: NSNotFound, length: 0))
                write("typed into \(responder.map { String(describing: type(of: $0)) } ?? "nothing")\n", to: out)
            }
            return
        case "action":
            // A menu command through the responder chain, as a click would.
            whenActive { [self] in
                let target = NSApp.target(forAction: Selector(arg)).map { String(describing: type(of: $0)) } ?? "nobody"
                let handled = NSApp.sendAction(Selector(arg), to: nil, from: nil)
                write("action \(arg) target=\(target) handled=\(handled)\n", to: out)
            }
            return
        case "statusmenu": write(app.debugStatusMenu(), to: out)
        case "show": app.showMainWindow(nil)
        case "close": app.debugMain?.window?.performClose(nil)
        case "settings": app.showSettings(nil)
        case "compose": app.newMessage(nil)
        case "visible":
            // The thread view only runs `read` while the user can see it; a
            // window behind others can't be seen. "on" pretends it can.
            app.store.detailIsVisible = arg == "on" ? { true } : { [weak app] in app?.debugMain?.window?.isVisible ?? false }
            app.store.detailBecameVisible()
        case "dock":
            if let p = DockIconPolicy(rawValue: arg) {
                app.settings.dockPolicy = p
                app.dock.update()
            }
        default: break
        }
        if let out, cmd["cmd"] != "dump", cmd["cmd"] != "snapshot", cmd["cmd"] != "statusmenu" {
            write("ok \(cmd["cmd"] ?? "")\n", to: out)
        }
    }

    /// Key events and menu commands need a key window. The deprecated forced
    /// activation is fine for a debug hook; the cooperative one is refused
    /// while another app is in front.
    private func whenActive(_ then: @escaping () -> Void, tries: Int = 40) {
        if NSApp.isActive, NSApp.keyWindow != nil {
            then()
            return
        }
        if tries % 10 == 0 {
            let window = NSApp.orderedWindows.first { $0.isVisible && $0.styleMask.contains(.titled) }
            // activate(ignoringOtherApps:) without its deprecation warning.
            NSApp.perform(NSSelectorFromString("activateIgnoringOtherApps:"), with: NSNumber(value: true))
            window?.makeKeyAndOrderFront(nil)
        }
        guard tries > 0 else {
            then()
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            MainActor.assumeIsolated { self?.whenActive(then, tries: tries - 1) }
        }
    }

    private func write(_ s: String, to url: URL?) {
        guard let url else { return }
        try? s.write(to: url, atomically: true, encoding: .utf8)
    }

    private func key(_ chars: String, mods: String) {
        var flags: NSEvent.ModifierFlags = []
        for m in mods.split(separator: ",") {
            switch m {
            case "cmd": flags.insert(.command)
            case "shift": flags.insert(.shift)
            case "ctrl": flags.insert(.control)
            case "opt": flags.insert(.option)
            default: break
            }
        }
        let window = NSApp.keyWindow ?? NSApp.mainWindow
        let codes: [String: UInt16] = ["\r": 36, " ": 49, "r": 15, "d": 2, "a": 0, "n": 45, "1": 18, "2": 19, "3": 20,
                                       "0": 29, "w": 13, ",": 43, "\u{1b}": 53, "\t": 48]
        let chars2 = flags.contains(.shift) ? chars.uppercased() : chars
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            guard let e = NSEvent.keyEvent(with: type, location: .zero, modifierFlags: flags, timestamp: ProcessInfo.processInfo.systemUptime,
                                           windowNumber: window?.windowNumber ?? 0, context: nil, characters: chars2,
                                           charactersIgnoringModifiers: chars, isARepeat: false,
                                           keyCode: codes[chars] ?? 0) else { continue }
            NSApp.sendEvent(e)
        }
    }

    private func snapshot(to url: URL?, title: String?) {
        let titled = NSApp.windows.filter { $0.isVisible && $0.styleMask.contains(.titled) }
        let named = title.flatMap { t in titled.first { $0.title == t } }
        guard let url, let window = named ?? NSApp.keyWindow.flatMap({ titled.contains($0) ? $0 : nil }) ?? titled.first,
              let view = window.contentView else { return }
        if let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) {
            view.cacheDisplay(in: view.bounds, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
        }
        if let web = Self.find(WKWebView.self, in: view) {
            web.takeSnapshot(with: nil) { image, _ in
                guard let image, let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) else { return }
                try? rep.representation(using: .png, properties: [:])?.write(to: url.deletingPathExtension().appendingPathExtension("web.png"))
            }
        }
    }

    static func find<T: NSView>(_ type: T.Type, in view: NSView) -> T? {
        if let v = view as? T { return v }
        for s in view.subviews { if let v = find(type, in: s) { return v } }
        return nil
    }

    private func dump(_ app: AppDelegate) -> String {
        var lines: [String] = []
        let store = app.store
        lines.append("activationPolicy: \(NSApp.activationPolicy() == .regular ? "regular" : NSApp.activationPolicy() == .accessory ? "accessory" : "prohibited")")
        lines.append("active: \(NSApp.isActive)")
        lines.append("mainMenu: \(NSApp.mainMenu?.items.map(\.title).joined(separator: " | ") ?? "-")")
        for w in NSApp.windows where w.isVisible || w.isMiniaturized {
            lines.append("window: \"\(w.title)\" subtitle=\"\(w.subtitle)\" key=\(w.isKeyWindow) main=\(w.isMainWindow) class=\(type(of: w)) frame=\(w.frame)")
            if w.isKeyWindow { lines.append("firstResponder: \(w.firstResponder.map { String(describing: type(of: $0)) } ?? "-") \((w.firstResponder as? NSView)?.accessibilityLabel() ?? "")") }
        }
        lines.append("mailbox: \(store.mailbox)")
        lines.append("selectedThread: \(store.selectedThreadID ?? "-")")
        lines.append("detail: \(store.detail.map { "\($0.id) msgs=\($0.msgs.count) fp=\($0.contentFingerprint)" } ?? "-")")
        lines.append("detailError: \(store.detailError?.errorDescription ?? "-")")
        lines.append("refreshError: \(store.refreshError?.errorDescription ?? "-")")
        lines.append("cli: \(store.cliURL?.path ?? "-")")
        lines.append("store: \(store.location.url.path) (\(store.location.source.rawValue))")
        lines.append("unreadInInbox: \(store.index.unreadInInbox)")
        lines.append("notifications: authorization=\(app.notifications.authorization.rawValue) alertStyle=\(app.notifications.alertStyle.rawValue) (0 = not determined / none)")
        lines += app.debugMain?.debugDump() ?? []
        lines.append("statusButton: title=\"\(app.debugStatusTitle)\"")
        return lines.joined(separator: "\n") + "\n"
    }
}

#endif
