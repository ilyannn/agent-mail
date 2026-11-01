// General: launch at login (SMAppService), the Dock icon policy, the CLI
// path (override, or what was found), and which store is in use.
import AgentMailKit
import AppKit
import Observation
import ServiceManagement

final class GeneralSettingsViewController: NSViewController {
    private let store: MailStore
    private let dock: DockPolicyController
    private let settings: AppSettings
    private let loginCheckbox = NSButton(checkboxWithTitle: "Open Agent Mail when you log in", target: nil, action: nil)
    private let loginNote = NSTextField.wrapping("")
    private let loginButton = NSButton(title: "Open Login Items…", target: nil, action: nil)
    private let dockPopup = NSPopUpButton()
    private let cliField = NSTextField()
    private let cliStatus = NSTextField.wrapping("")
    private let storeLabel = NSTextField.wrapping("", color: .labelColor)
    private var grid: NSGridView!
    private var observation: Task<Void, Never>?

    init(store: MailStore, dock: DockPolicyController, settings: AppSettings) {
        self.store = store
        self.dock = dock
        self.settings = settings
        super.init(nibName: nil, bundle: nil)
        title = "General"
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func loadView() {
        loginCheckbox.target = self
        loginCheckbox.action = #selector(toggleLogin(_:))
        loginButton.target = self
        loginButton.action = #selector(openLoginItems(_:))
        loginButton.controlSize = .small

        for policy in DockIconPolicy.allCases {
            dockPopup.addItem(withTitle: policy.title)
            dockPopup.lastItem?.representedObject = policy.rawValue
        }
        dockPopup.target = self
        dockPopup.action = #selector(dockChanged(_:))

        cliField.placeholderString = "Find automatically"
        cliField.target = self
        cliField.action = #selector(cliEdited(_:))
        cliField.lineBreakMode = .byTruncatingMiddle
        cliField.setAccessibilityLabel("Command-line tool path")
        let choose = NSButton(title: "Choose…", target: self, action: #selector(chooseCLI(_:)))
        let reset = NSButton(title: "Use Default", target: self, action: #selector(resetCLI(_:)))
        let cliRow = NSStackView(views: [cliField, choose, reset])
        cliRow.spacing = 6
        cliField.widthAnchor.constraint(greaterThanOrEqualToConstant: 240).isActive = true

        storeLabel.isSelectable = true
        storeLabel.font = .systemFont(ofSize: NSFont.smallSystemFontSize)
        let reveal = NSButton(title: "Show in Finder", target: self, action: #selector(revealStore(_:)))
        reveal.controlSize = .small

        grid = NSGridView(views: [
            [NSTextField.label("Login:"), loginCheckbox],
            [NSGridCell.emptyContentView, stack([loginNote, loginButton])],
            [NSTextField.label("Dock icon:"), dockPopup],
            [NSTextField.label("Command-line tool:"), cliRow],
            [NSGridCell.emptyContentView, cliStatus],
            [NSTextField.label("Store:"), stack([storeLabel, reveal])],
        ])
        grid.column(at: 0).xPlacement = .trailing
        grid.rowAlignment = .firstBaseline
        grid.columnSpacing = 8
        grid.rowSpacing = 10
        grid.translatesAutoresizingMaskIntoConstraints = false
        cliStatus.preferredMaxLayoutWidth = 380
        loginNote.preferredMaxLayoutWidth = 380
        storeLabel.preferredMaxLayoutWidth = 380

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

    private func stack(_ views: [NSView]) -> NSStackView {
        let s = NSStackView(views: views)
        s.orientation = .vertical
        s.alignment = .leading
        s.spacing = 4
        return s
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let changes = Observations { [store] in (store.cliURL, store.cliError) }
        observation = Task { [weak self] in
            for await _ in changes { self?.updateCLI() }
        }
    }

    override func viewWillAppear() {
        super.viewWillAppear()
        updateLogin()
        if let i = DockIconPolicy.allCases.firstIndex(of: settings.dockPolicy) { dockPopup.selectItem(at: i) }
        cliField.stringValue = settings.cliPath ?? ""
        updateCLI()
        let source = switch store.location.source {
        case .environment: "from the AGENT_MAIL_DIR environment variable"
        case .settings: "from the storePath default"
        case .standard: "the default location"
        }
        storeLabel.stringValue = store.location.url.path + "\n" + source
        storeLabel.toolTip = "The store is chosen at launch: AGENT_MAIL_DIR, else the storePath default, else ~/.local/share/agent-mail. The app passes it to the CLI."
    }

    // MARK: Launch at login

    private func updateLogin() {
        let status = SMAppService.mainApp.status
        loginCheckbox.state = status == .enabled || status == .requiresApproval ? .on : .off
        loginNote.stringValue = "Waiting for your approval in System Settings › General › Login Items."
        grid.row(at: 1).isHidden = status != .requiresApproval
        (parent as? SettingsTabViewController)?.fitWindow(to: self)
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        (parent as? SettingsTabViewController)?.fitWindow(to: self)
    }

    @objc private func toggleLogin(_ sender: NSButton) {
        do {
            if sender.state == .on {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            presentError(error)
        }
        updateLogin()
    }

    @objc private func openLoginItems(_ sender: Any?) {
        SMAppService.openSystemSettingsLoginItems()
    }

    // MARK: Dock

    @objc private func dockChanged(_ sender: NSPopUpButton) {
        guard let raw = sender.selectedItem?.representedObject as? String, let policy = DockIconPolicy(rawValue: raw)
        else { return }
        settings.dockPolicy = policy
        dock.update()
        if NSApp.activationPolicy() == .regular { NSApp.activate() }
    }

    // MARK: CLI

    private func updateCLI() {
        if let error = store.cliError {
            cliStatus.stringValue = [error.errorDescription, error.failureReason].compactMap { $0 }.joined(separator: " ")
            cliStatus.textColor = .systemRed
        } else if let url = store.cliURL {
            cliStatus.stringValue = "Using \(url.path)"
            cliStatus.textColor = .secondaryLabelColor
        } else {
            cliStatus.stringValue = "Looking for agent-mail…"
            cliStatus.textColor = .secondaryLabelColor
        }
    }

    @objc private func cliEdited(_ sender: NSTextField) {
        let path = sender.stringValue.trimmingCharacters(in: .whitespaces)
        settings.cliPath = path.isEmpty ? nil : path
        store.cliPathChanged()
    }

    @objc private func chooseCLI(_ sender: Any?) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.treatsFilePackagesAsDirectories = true
        panel.showsHiddenFiles = true
        panel.message = "Choose the agent-mail executable (bin/agent-mail in the repo, or its link in ~/.local/bin)."
        guard let window = view.window else { return }
        panel.beginSheetModal(for: window) { [weak self] response in
            guard response == .OK, let url = panel.url, let self else { return }
            cliField.stringValue = url.path
            cliEdited(cliField)
        }
    }

    @objc private func resetCLI(_ sender: Any?) {
        cliField.stringValue = ""
        cliEdited(cliField)
    }

    @objc private func revealStore(_ sender: Any?) {
        let url = store.location.url
        if FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        } else {
            NSSound.beep()
        }
    }
}
