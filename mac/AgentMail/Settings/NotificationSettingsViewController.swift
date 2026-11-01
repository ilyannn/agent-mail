// Notifications: whether they are allowed and in which style (inline Reply
// needs Alerts), a way to ask or to open System Settings, and whether mail
// between agents notifies too.
import AppKit
import Observation
import UserNotifications

final class NotificationSettingsViewController: NSViewController {
    private let notifications: NotificationController
    private let settings: AppSettings
    private let statusLabel = NSTextField.label()
    private let hint = NSTextField.wrapping("")
    private let askButton = NSButton(title: "Allow Notifications…", target: nil, action: nil)
    private let openButton = NSButton(title: "Open Notification Settings…", target: nil, action: nil)
    private let toMe = NSButton(checkboxWithTitle: "Messages to you", target: nil, action: nil)
    private let agentTraffic = NSButton(checkboxWithTitle: "Messages between agents", target: nil, action: nil)
    private var observation: Task<Void, Never>?

    init(notifications: NotificationController, settings: AppSettings) {
        self.notifications = notifications
        self.settings = settings
        super.init(nibName: nil, bundle: nil)
        title = "Notifications"
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func loadView() {
        askButton.target = self
        askButton.action = #selector(ask(_:))
        openButton.target = self
        openButton.action = #selector(openSettings(_:))
        toMe.state = .on
        toMe.isEnabled = false
        toMe.toolTip = "Mail to you always notifies while notifications are allowed."
        agentTraffic.target = self
        agentTraffic.action = #selector(toggleAgentTraffic(_:))
        hint.preferredMaxLayoutWidth = 380

        let buttons = NSStackView(views: [askButton, openButton])
        buttons.spacing = 8
        let grid = NSGridView(views: [
            [NSTextField.label("Status:"), statusLabel],
            [NSGridCell.emptyContentView, hint],
            [NSGridCell.emptyContentView, buttons],
            [NSTextField.label("Notify about:"), toMe],
            [NSGridCell.emptyContentView, agentTraffic],
        ])
        grid.column(at: 0).xPlacement = .trailing
        grid.rowAlignment = .firstBaseline
        grid.columnSpacing = 8
        grid.rowSpacing = 10
        grid.row(at: 3).topPadding = 8
        grid.translatesAutoresizingMaskIntoConstraints = false

        let container = NSView()
        container.addSubview(grid)
        NSLayoutConstraint.activate([
            grid.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 24),
            grid.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -24),
            grid.topAnchor.constraint(equalTo: container.topAnchor, constant: 20),
            grid.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -20),
            container.widthAnchor.constraint(greaterThanOrEqualToConstant: 560),
        ])
        view = container
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let changes = Observations { [notifications] in (notifications.authorization, notifications.alertStyle) }
        observation = Task { [weak self] in
            for await _ in changes { self?.update() }
        }
    }

    override func viewWillAppear() {
        super.viewWillAppear()
        agentTraffic.state = settings.notifyAgentTraffic ? .on : .off
        update()
        Task { await notifications.refreshSettings() }
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        (parent as? SettingsTabViewController)?.fitWindow(to: self)
    }

    private func update() {
        let replyHint = "To reply from a notification, choose the Alerts style for Agent Mail in System Settings › Notifications. Banners disappear before you can type."
        switch notifications.authorization {
        case .notDetermined:
            statusLabel.stringValue = "Not requested yet"
            hint.stringValue = "Agent Mail notifies you when an agent writes to you. Allow notifications to get them."
            askButton.isHidden = false
        case .denied:
            statusLabel.stringValue = "Off"
            hint.stringValue = "Notifications are turned off for Agent Mail. Turn them on in System Settings › Notifications."
            askButton.isHidden = true
        default:
            askButton.isHidden = true
            switch notifications.alertStyle {
            case .alert:
                statusLabel.stringValue = "On, as alerts"
                hint.stringValue = "Alerts stay until you act on them, so you can reply or close the thread from the notification."
            case .banner:
                statusLabel.stringValue = "On, as banners"
                hint.stringValue = replyHint
            default:
                statusLabel.stringValue = "On, without banners or alerts"
                hint.stringValue = "Notifications go to Notification Center only. " + replyHint
            }
        }
    }

    @objc private func ask(_ sender: Any?) {
        Task {
            let granted = await notifications.requestAuthorization()
            if granted, notifications.alertStyle != .alert {
                await FirstRun.offerAlertStyle(over: view.window, notifications: notifications)
            }
        }
    }

    @objc private func openSettings(_ sender: Any?) {
        notifications.openSystemSettings()
    }

    @objc private func toggleAgentTraffic(_ sender: NSButton) {
        settings.notifyAgentTraffic = sender.state == .on
    }
}
