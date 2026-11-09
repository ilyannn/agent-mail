// The app's one model object. It owns the CLI client, the store watcher and
// the refresh loop, holds the latest status, the selected thread and the new
// thread being written (the draft), and runs writes. Writes never touch
// local state: the CLI changes the store, FSEvents reports it, and the next
// status run brings the change in. View controllers observe it
// (Observations) and forward actions to it.
import AgentMailKit
import AppKit
import Observation

@Observable
final class MailStore {
    let location: StoreLocation
    @ObservationIgnored let settings: AppSettings

    private(set) var cliURL: URL?
    private(set) var cliError: AgentMailError?
    /// The latest status; replaced on every refresh (its `now` moves).
    private(set) var status: MailStatus?
    /// Mailboxes derived from the status; replaced only when they change.
    private(set) var index: MailboxIndex = .empty
    /// The last refresh failed; the previous status stays on screen.
    private(set) var refreshError: AgentMailError?
    private(set) var hasLoaded = false

    /// Switching mailboxes drops a selection the new one doesn't hold; a
    /// thread that merely leaves the mailbox on refresh (say, after a reply)
    /// stays on screen.
    var mailbox: Mailbox {
        didSet {
            guard mailbox != oldValue else { return }
            settings.mailbox = mailbox
            dropSelectionUnlessShown()
        }
    }
    /// How All Mail and participant mailboxes are sorted; saved in the settings.
    var threadOrder: ThreadOrder {
        didSet {
            guard threadOrder != oldValue else { return }
            settings.threadOrder = threadOrder
        }
    }
    /// Which of a participant's threads its mailbox shows; one setting for
    /// every participant, saved in the settings. Like a mailbox switch, a new
    /// filter drops a selection it hides.
    var participantFilter: ParticipantFilter {
        didSet {
            guard participantFilter != oldValue else { return }
            settings.participantFilter = participantFilter
            dropSelectionUnlessShown()
        }
    }
    private(set) var selectedThreadID: String?
    /// The selected thread, once `read` has returned it.
    private(set) var detail: ThreadDetail?
    private(set) var detailError: AgentMailError?
    /// Threads with a write in flight.
    private(set) var busyThreadIDs: Set<String> = []

    /// The new thread being written (File › New Message, or Forward on a
    /// closed thread), at most one. Selecting a thread keeps it.
    private(set) var newDraft: NewThreadDraft?
    /// The thread view shows the draft; no thread is selected meanwhile.
    private(set) var isShowingDraft = false
    private(set) var isSendingDraft = false
    /// The thread a forward draft carries, once loaded, for its preview.
    private(set) var forwardSource: ThreadDetail?
    private(set) var forwardSourceError: AgentMailError?

    /// Unsent replies by thread id; the thread view saves into it on every
    /// selection change, and a failed notification reply lands here.
    @ObservationIgnored var drafts: [String: String] = [:]
    /// Whether the thread view is on screen: `read` marks a thread seen, so
    /// it only runs when the user can actually see the thread.
    @ObservationIgnored var detailIsVisible: () -> Bool = { false }
    @ObservationIgnored var onNewMessages: ([StoredMessage]) -> Void = { _ in }
    @ObservationIgnored var onThreadRead: (ThreadDetail) -> Void = { _ in }

    @ObservationIgnored private var client: CLIClient?
    @ObservationIgnored private var watcher: StoreWatcher?
    @ObservationIgnored private var loops: [Task<Void, Never>] = []
    @ObservationIgnored private var detailTask: Task<Void, Never>?
    @ObservationIgnored private var cache: [String: ThreadDetail] = [:]
    @ObservationIgnored private var tracker: NewMessageTracker
    @ObservationIgnored private var savedSeen: Set<String>?
    @ObservationIgnored private let seenFile: SeenSetFile
    @ObservationIgnored private let reader: StoreReader
    @ObservationIgnored private var scheduler: RefreshScheduler?
    @ObservationIgnored private var requestedAt: ContinuousClock.Instant?
    /// The thread selected when the draft came on screen, for Discard to go
    /// back to.
    @ObservationIgnored private var threadBeforeDraft: String?
    /// A thread just sent from the draft: once a refresh brings it in, the
    /// mailbox switches to All Mail if the list doesn't show it.
    @ObservationIgnored private var pendingReveal: String?

    init(settings: AppSettings, location: StoreLocation) {
        self.settings = settings
        self.location = location
        mailbox = settings.mailbox
        threadOrder = settings.threadOrder
        participantFilter = settings.participantFilter
        reader = StoreReader(root: location.url)
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: Bundle.main.bundleIdentifier ?? "AgentMail")
        seenFile = SeenSetFile(directory: support, store: location.url)
        savedSeen = seenFile.load()
        tracker = NewMessageTracker(saved: savedSeen)
    }

    func start() {
        guard scheduler == nil else { return }
        scheduler = RefreshScheduler { [weak self] in await self?.performRefresh() }
        let watcher = StoreWatcher(root: location.url)
        self.watcher = watcher
        let events = watcher.events()
        loops.append(Task { [weak self] in
            for await _ in events { self?.refresh(reason: "store event") }
        })
        // Liveness (a dead agent, a stale heartbeat) changes without any file
        // event, and FSEvents can't watch a store that doesn't exist yet.
        loops.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                self?.watcher?.rewatchIfStoreAppeared()
                self?.refresh(reason: "timer")
            }
        })
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil,
                                                          queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh(reason: "wake") }
        }
        refresh(reason: "launch")
    }

    // MARK: Reading

    func refresh(reason: String = "request") {
        if requestedAt == nil { requestedAt = .now }
        guard let scheduler else { return }
        Task { await scheduler.request() }
    }

    /// Settings changed the CLI path: find it again and refresh.
    func cliPathChanged() {
        client = nil
        cliURL = nil
        cliError = nil
        refresh(reason: "cli path changed")
    }

    private func ensureClient() async -> CLIClient? {
        if let client { return client }
        do {
            let url = try await CLILocator(override: settings.cliPath).locate()
            cliURL = url
            cliError = nil
            let env = CLIClient.environment(base: ProcessInfo.processInfo.environment, store: location.url)
            let c = CLIClient(executable: url, environment: env,
                              directory: FileManager.default.homeDirectoryForCurrentUser)
            client = c
            return c
        } catch {
            cliError = error
            refreshError = error
            return nil
        }
    }

    private func performRefresh() async {
        let requested = requestedAt ?? .now
        requestedAt = nil
        guard let client = await ensureClient() else { return }
        do {
            let r = try await client.status()
            Timing.note("status --json \(Timing.ms(r.duration)) (\(r.value.threads.count) threads); "
                + "\(Timing.ms(.now - requested)) since requested")
            apply(r.value)
        } catch {
            refreshError = error
            Timing.note("status failed: \(error.localizedDescription)")
        }
    }

    private func apply(_ st: MailStatus) {
        let newIndex = MailboxIndex(status: st)
        status = st
        if newIndex != index { index = newIndex }
        if refreshError != nil { refreshError = nil }
        hasLoaded = true
        if let id = pendingReveal, newIndex.thread(id) != nil {
            pendingReveal = nil
            if selectedThreadID == id, !isShown(id) { mailbox = .allMail }
        }
        announceNewMessages(st)
        if let id = selectedThreadID, let t = newIndex.thread(id),
           detail?.id != id || detail?.contentFingerprint != t.contentFingerprint {
            loadDetail(debounce: false)
        }
    }

    private func announceNewMessages(_ st: MailStatus) {
        let reader = self.reader
        let fresh = tracker.update(threads: st.threads) { reader.messageFiles(thread: $0) }
        if tracker.seen != savedSeen {
            do {
                try seenFile.save(tracker.seen)
                savedSeen = tracker.seen
            } catch {
                Timing.note("couldn't save the seen set: \(error.localizedDescription)")
            }
        }
        guard !fresh.isEmpty else { return }
        let messages = fresh.compactMap { reader.message(thread: $0.threadID, file: $0.file) }
        Timing.note("new message files: \(messages.map(\.file).joined(separator: ", "))")
        onNewMessages(messages)
    }

    // MARK: Selection

    /// The thread list's threads: the mailbox in the chosen order, filtered
    /// when it is a participant's.
    var shownThreads: [ThreadSummary] { index.threads(in: mailbox, order: threadOrder, filter: participantFilter) }

    /// Whether the thread list holds the draft's row: in the mailboxes the
    /// thread it starts will be in (MailboxIndex.showsDraft).
    var showsDraftRow: Bool {
        newDraft.map { index.showsDraft($0, in: mailbox, filter: participantFilter) } ?? false
    }

    /// Whether the thread list holds thread `id`.
    func isShown(_ id: String) -> Bool {
        index.threads(in: mailbox, filter: participantFilter).contains { $0.id == id }
    }

    private func dropSelectionUnlessShown() {
        if let id = selectedThreadID, !isShown(id) { select(threadID: nil) }
    }

    /// Selects a thread (nil: none); the thread view stops showing the draft.
    func select(threadID id: String?) {
        if isShowingDraft { isShowingDraft = false }
        guard id != selectedThreadID else { return }
        selectedThreadID = id
        detailError = nil
        loadDetail(debounce: true)
    }

    /// Shows thread `id` from a link (a forward, or its source): All Mail
    /// when the list doesn't hold it. False when the thread is unknown.
    @discardableResult
    func reveal(threadID id: String) -> Bool {
        guard index.thread(id) != nil else { return false }
        if !isShown(id) { mailbox = .allMail }
        select(threadID: id)
        return true
    }

    /// Whether thread `id` is open, by its loaded copy when it is the
    /// selected one, else by the latest status.
    func isOpen(_ id: String) -> Bool {
        (detail?.id == id ? detail?.isOpen : nil) ?? index.thread(id)?.isOpen ?? cache[id]?.isOpen ?? false
    }

    /// A thread's subject, as far as anything loaded knows it.
    func subject(of id: String) -> String {
        index.thread(id)?.subject ?? cache[id]?.subject ?? ""
    }

    /// The thread view came on screen: load what it should show.
    func detailBecameVisible() {
        loadDetail(debounce: false)
    }

    /// Shows the cached thread at once, and runs `read` when the cache is
    /// stale or the thread has unread messages (reading marks them seen).
    private func loadDetail(debounce: Bool) {
        detailTask?.cancel()
        guard let id = selectedThreadID else {
            detail = nil
            return
        }
        let summary = index.thread(id)
        let cached = cache[id]
        if let cached, detail?.id != id { detail = cached }
        if cached == nil, detail?.id != id { detail = nil }
        if let cached, let summary, cached.contentFingerprint == summary.contentFingerprint, summary.unread == 0 {
            return
        }
        guard detailIsVisible() else { return }
        detailTask = Task { [weak self] in
            if debounce {
                try? await Task.sleep(for: .milliseconds(120))
                if Task.isCancelled { return }
            }
            guard let self, let client = await self.ensureClient() else { return }
            do {
                let r = try await client.thread(id)
                Timing.note("read \(id) \(Timing.ms(r.duration))")
                self.cache[id] = r.value
                if self.selectedThreadID == id {
                    self.detail = r.value
                    self.detailError = nil
                }
                self.onThreadRead(r.value)
            } catch let error as AgentMailError {
                if Task.isCancelled { return }
                if self.selectedThreadID == id { self.detailError = error }
            } catch {}
        }
    }

    // MARK: Draft

    /// File › New Message: shows the draft, starting one when there is none.
    /// `recipient` fills To when it is empty.
    func startNewMessage(to recipient: String?) {
        if newDraft == nil {
            newDraft = NewThreadDraft(to: recipient ?? "")
        } else if let recipient {
            editDraft { $0.fillRecipient(recipient) }
        }
        showDraft()
    }

    /// Forward on a closed thread: a new draft carrying `source`, in place
    /// of any other (the view asks first when that one has typed content).
    func startForward(_ source: ForwardSource) {
        newDraft = .forwarding(source, subject: subject(of: source.threadID))
        forwardSource = nil
        forwardSourceError = nil
        showDraft()
        loadForwardSource()
    }

    /// Puts the draft in the thread view, remembering the selected thread.
    func showDraft() {
        guard newDraft != nil, !isShowingDraft else { return }
        threadBeforeDraft = selectedThreadID
        select(threadID: nil)
        isShowingDraft = true
    }

    /// An edit to the draft's fields.
    func editDraft(_ change: (inout NewThreadDraft) -> Void) {
        guard var draft = newDraft else { return }
        change(&draft)
        if draft != newDraft { newDraft = draft }
    }

    /// Throws the draft away; the thread view goes back to the thread shown
    /// before it, when the list still holds it.
    func discardDraft() {
        let back = threadBeforeDraft
        let showing = isShowingDraft
        clearDraft()
        guard showing else { return }
        select(threadID: back.flatMap { isShown($0) ? $0 : nil })
    }

    private func clearDraft() {
        newDraft = nil
        forwardSource = nil
        forwardSourceError = nil
        threadBeforeDraft = nil
    }

    /// Sends the draft as it stands (a new thread, or a forward). On success
    /// the draft goes and the new thread is selected; once a refresh brings
    /// it in, the mailbox switches to All Mail if the list doesn't show it.
    /// A failure keeps the draft.
    func sendDraft() async throws(AgentMailError) {
        guard let draft = newDraft, !isSendingDraft else { return }
        isSendingDraft = true
        defer { isSendingDraft = false }
        let id = if let source = draft.forward {
            try await forward(threadID: source.threadID, message: source.message, to: draft.recipient,
                              subject: draft.subject, note: draft.body)
        } else {
            try await send(to: draft.recipient, subject: draft.subject, body: draft.body)
        }
        guard newDraft?.id == draft.id else { return }
        let showing = isShowingDraft
        clearDraft()
        guard showing else { return }
        if id.isEmpty {
            select(threadID: nil)
        } else {
            pendingReveal = id
            select(threadID: id)
        }
    }

    /// The forwarded thread, for the draft's preview: the cached copy at
    /// once, and `read` when that is stale or unread. Reading marks it seen,
    /// which it is: the preview shows it.
    private func loadForwardSource() {
        guard let id = newDraft?.forward?.threadID else { return }
        let summary = index.thread(id)
        if let cached = cache[id] {
            forwardSource = cached
            if let summary, cached.contentFingerprint == summary.contentFingerprint, summary.unread == 0 { return }
        }
        Task { [weak self] in
            guard let self, let client = await self.ensureClient() else { return }
            do {
                let r = try await client.thread(id)
                self.cache[id] = r.value
                if self.newDraft?.forward?.threadID == id {
                    self.forwardSource = r.value
                    self.forwardSourceError = nil
                }
                self.onThreadRead(r.value)
            } catch let error as AgentMailError {
                if self.newDraft?.forward?.threadID == id { self.forwardSourceError = error }
            } catch {}
        }
    }

    // MARK: Writing

    private func requireClient() async throws(AgentMailError) -> CLIClient {
        if let c = await ensureClient() { return c }
        throw cliError ?? .cliNotFound(searched: [])
    }

    func reply(threadID: String, to recipient: String, body: String) async throws(AgentMailError) {
        let client = try await requireClient()
        busyThreadIDs.insert(threadID)
        defer { busyThreadIDs.remove(threadID) }
        let began = ContinuousClock.now
        try await client.reply(threadID: threadID, to: recipient, body: body)
        drafts[threadID] = nil
        Timing.note("reply \(threadID) \(Timing.ms(.now - began))")
    }

    /// Starts a thread; returns its id.
    func send(to recipient: String, subject: String?, body: String) async throws(AgentMailError) -> String {
        let client = try await requireClient()
        let began = ContinuousClock.now
        let id = try await client.send(to: recipient, subject: subject, body: body)
        Timing.note("send \(Timing.ms(.now - began))")
        return id
    }

    /// Forwards a thread, or one message of it, in a new thread to
    /// `recipient`; returns the new thread's id.
    func forward(threadID: String, message: String?, to recipient: String, subject: String?,
                 note: String) async throws(AgentMailError) -> String {
        let client = try await requireClient()
        let began = ContinuousClock.now
        let id = try await client.forward(threadID: threadID, message: message, to: recipient, subject: subject,
                                          note: note)
        Timing.note("forward \(threadID) \(Timing.ms(.now - began))")
        return id
    }

    func close(threadID: String, note: String = "") async throws(AgentMailError) {
        let client = try await requireClient()
        busyThreadIDs.insert(threadID)
        defer { busyThreadIDs.remove(threadID) }
        try await client.close(threadID: threadID, text: note)
        drafts[threadID] = nil
    }

    /// Adds `folder` to the agent directory, on call (`agents add DIR`);
    /// returns the name the CLI gave it. The sidebar lists it once the
    /// store watcher brings the change in.
    func addAgentFolder(_ folder: URL) async throws(AgentMailError) -> String? {
        let client = try await requireClient()
        let began = ContinuousClock.now
        let name = try await client.addAgent(directory: folder.path)
        Timing.note("agents add \(name ?? "?") \(Timing.ms(.now - began))")
        return name
    }

    /// Puts directory agent `name` on call or takes it off.
    func setOnCall(_ name: String, _ onCall: Bool) async throws(AgentMailError) {
        let client = try await requireClient()
        let began = ContinuousClock.now
        try await client.setOnCall(name, onCall)
        Timing.note("oncall \(onCall ? "add" : "remove") \(name) \(Timing.ms(.now - began))")
    }

    #if DEBUG
    /// Tests: shows `st` as a refresh would, without the new-message
    /// bookkeeping (which reads the store and saves the seen set).
    func debugShow(_ st: MailStatus) {
        status = st
        index = MailboxIndex(status: st)
        hasLoaded = true
    }
    #endif

    // MARK: Paths

    func threadDirectory(_ id: String) -> URL { reader.threadDirectory(id) }
    func filesDirectory(_ id: String) -> URL { reader.filesDirectory(id) }
}
