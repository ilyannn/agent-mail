// Settings (⌘,): a tab view controller in toolbar style, per the HIG: the
// window title follows the pane, the last pane comes back, and there is no
// minimize or zoom.
import AppKit

final class SettingsWindowController: NSWindowController {
    init(store: MailStore, notifications: NotificationController, dock: DockPolicyController, settings: AppSettings) {
        let tabs = SettingsTabViewController(settings: settings)
        tabs.tabStyle = .toolbar
        tabs.transitionOptions = [.allowUserInteraction]
        let general = NSTabViewItem(viewController: GeneralSettingsViewController(store: store, dock: dock, settings: settings))
        general.label = "General"
        general.image = NSImage(systemSymbolName: "gearshape", accessibilityDescription: "General")
        let alerts = NSTabViewItem(viewController: NotificationSettingsViewController(notifications: notifications,
                                                                                     settings: settings))
        alerts.label = "Notifications"
        alerts.image = NSImage(systemSymbolName: "bell.badge", accessibilityDescription: "Notifications")
        tabs.addTabViewItem(general)
        tabs.addTabViewItem(alerts)
        tabs.selectedTabViewItemIndex = min(max(settings.settingsPane, 0), 1)

        let window = NSWindow(contentViewController: tabs)
        window.styleMask = [.titled, .closable]
        window.toolbarStyle = .preference
        window.identifier = NSUserInterfaceItemIdentifier("settings")
        window.isReleasedWhenClosed = false
        window.tabbingMode = .disallowed
        super.init(window: window)
        window.center()
    }

    required init?(coder: NSCoder) { fatalError("not used") }
}

final class SettingsTabViewController: NSTabViewController {
    private let settings: AppSettings

    init(settings: AppSettings) {
        self.settings = settings
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func tabView(_ tabView: NSTabView, didSelect tabViewItem: NSTabViewItem?) {
        super.tabView(tabView, didSelect: tabViewItem)
        settings.settingsPane = selectedTabViewItemIndex
        if let vc = tabViewItem?.viewController { fitWindow(to: vc) }
    }

    /// Resizes the window to the pane's fitting size, keeping its top edge.
    func fitWindow(to pane: NSViewController) {
        guard let window = view.window, tabViewItems.indices.contains(selectedTabViewItemIndex),
              tabViewItems[selectedTabViewItemIndex].viewController === pane else { return }
        pane.view.layoutSubtreeIfNeeded()
        let size = pane.view.fittingSize
        let content = window.contentRect(forFrameRect: window.frame)
        guard abs(content.height - size.height) > 1 || abs(content.width - size.width) > 1 else { return }
        let frame = window.frameRect(forContentRect: NSRect(x: content.minX, y: content.maxY - size.height,
                                                           width: size.width, height: size.height))
        window.setFrame(frame, display: true, animate: window.isVisible)
    }
}
