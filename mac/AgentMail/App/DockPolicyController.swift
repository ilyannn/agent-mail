// The Dock icon setting: the app is an LSUIElement (accessory) and switches
// to the regular activation policy, with Dock icon and menu bar, always,
// never, or while one of its windows is open.
import AppKit

final class DockPolicyController {
    private let settings: AppSettings
    private var observers: [NSObjectProtocol] = []

    init(settings: AppSettings) {
        self.settings = settings
        let center = NotificationCenter.default
        for name in [NSWindow.didBecomeKeyNotification, NSWindow.willCloseNotification,
                     NSWindow.didMiniaturizeNotification, NSWindow.didDeminiaturizeNotification,
                     NSWindow.didChangeOcclusionStateNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                // After the close has finished, so the closing window no
                // longer counts as visible.
                DispatchQueue.main.async { MainActor.assumeIsolated { self?.update() } }
            })
        }
    }

    /// A titled, non-panel window that is on screen or in the Dock.
    var hasOpenWindow: Bool {
        NSApp.windows.contains { w in
            (w.isVisible || w.isMiniaturized) && w.styleMask.contains(.titled) && !(w is NSPanel)
        }
    }

    var desiredPolicy: NSApplication.ActivationPolicy {
        switch settings.dockPolicy {
        case .always: .regular
        case .never: .accessory
        case .whileWindowOpen: hasOpenWindow ? .regular : .accessory
        }
    }

    func update() {
        let desired = desiredPolicy
        guard NSApp.activationPolicy() != desired else { return }
        NSApp.setActivationPolicy(desired)
    }

    /// Call before showing a window: the policy switches first, so the window
    /// comes up with the menu bar in place.
    func prepareToShowWindow() {
        if settings.dockPolicy != .never, NSApp.activationPolicy() != .regular {
            NSApp.setActivationPolicy(.regular)
        }
    }
}
