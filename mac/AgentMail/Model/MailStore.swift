// The app's one model object. It owns the CLI client, the store watcher and
// the refresh loop, holds the latest status and the selected thread, and
// runs writes. Writes never touch local state: the CLI changes the store,
// FSEvents reports it, and the next status run brings the change in.
// View controllers observe it (Observations) and forward actions to it.
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
            if let id = selectedThreadID, !index.threads(in: mailbox).contains(where: { $0.id == id }) {
                select(threadID: nil)
            }
        }
    }
    /// How All Mail and agent mailboxes are sorted; saved in the settings.
    var threadOrder: ThreadOrder {
        didSet {
            guard threadOrder != oldValue else { return }
            settings.threadOrder = threadOrder
        }
    }
    private(set) var selectedThreadID: String?
    /// The selected thread, once `read` has returned it.
    private(set) var detail: ThreadDetail?
    private(set) var detailError: AgentMailError?
    /// Threads with a write in flight.
    private(set) var busyThreadIDs: Set<String> = []

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

    init(settings: AppSettings, location: StoreLocation) {
        self.settings = settings
        self.location = location
        mailbox = settings.mailbox
        threadOrder = settings.threadOrder
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

    func select(threadID id: String?) {
        guard id != selectedThreadID else { return }
        selectedThreadID = id
        detailError = nil
        loadDetail(debounce: true)
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

    func close(threadID: String, note: String = "") async throws(AgentMailError) {
        let client = try await requireClient()
        busyThreadIDs.insert(threadID)
        defer { busyThreadIDs.remove(threadID) }
        try await client.close(threadID: threadID, text: note)
        drafts[threadID] = nil
    }

    // MARK: Paths

    func threadDirectory(_ id: String) -> URL { reader.threadDirectory(id) }
    func filesDirectory(_ id: String) -> URL { reader.filesDirectory(id) }
}
