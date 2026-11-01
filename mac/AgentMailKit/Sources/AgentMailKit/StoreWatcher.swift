// FSEvents on the store root, as an AsyncStream that yields once per batch
// of relevant events. File-level events with ~0.3 s latency; git's own
// writes (every CLI write commits), the lock directory, drafts and rendered
// views are ignored. Duty records need care: an on-duty agent rewrites its
// record every few seconds, and FSEvents flags every rewrite as Created, so
// the watcher compares the set of record names and only a record appearing
// or going away counts. Liveness changes without any file event (a stale
// heartbeat, a dead session) are the periodic refresh's job.
import CoreServices
import Foundation
import Synchronization

public enum StoreEventFilter {
    public enum Kind: Equatable, Sendable {
        case relevant
        case ignored
        /// Under duty/: relevant only if the set of records changed.
        case duty
    }

    static let rescan = FSEventStreamEventFlags(kFSEventStreamEventFlagMustScanSubDirs
        | kFSEventStreamEventFlagRootChanged | kFSEventStreamEventFlagKernelDropped
        | kFSEventStreamEventFlagUserDropped)

    /// What an event at `path` (absolute, symlinks resolved) under the store
    /// `root` (same form) means for a refresh.
    public static func classify(path: String, flags: FSEventStreamEventFlags, root: String) -> Kind {
        if flags & rescan != 0 { return .relevant }
        let rootSlash = root.hasSuffix("/") ? root : root + "/"
        guard path.hasPrefix(rootSlash) || path == root else { return .ignored }
        let rel = path == root ? "" : String(path.dropFirst(rootSlash.count))
        let parts = rel.split(separator: "/")
        guard let top = parts.first else { return .ignored }
        switch top {
        case "threads":
            // Temporary message files (`.name.tmp`) become real by a rename,
            // which is reported under the final name.
            return (parts.last?.hasPrefix(".") ?? false) ? .ignored : .relevant
        case "seen":
            return .relevant
        case "duty":
            return .duty
        default:
            // .git, .lock, drafts, view, .gitignore
            return .ignored
        }
    }

    /// Names of the duty records in `dir`.
    public static func dutyRecords(in dir: String) -> Set<String> {
        Set(((try? FileManager.default.contentsOfDirectory(atPath: dir)) ?? []).filter { $0.hasSuffix(".edn") })
    }
}

public final class StoreWatcher: Sendable {
    /// The store root with symlinks resolved, as FSEvents reports paths.
    public let root: String
    private let latency: CFTimeInterval
    private let queue = DispatchQueue(label: "agent-mail.store-watcher")
    /// FSEventStreamRef is a plain pointer; it is only touched under the lock.
    private struct Stream: @unchecked Sendable { let ref: FSEventStreamRef }
    private struct State {
        var stream: Stream?
        var watched: String?
        var continuation: AsyncStream<Void>.Continuation?
        var dutyRecords: Set<String> = []
    }
    private let state = Mutex(State())

    public init(root: URL, latency: CFTimeInterval = 0.3) {
        self.root = Self.realPath(root.path)
        self.latency = latency
    }

    deinit { stop() }

    /// Starts watching; the stream yields after each batch with a relevant
    /// event. Watches the nearest existing ancestor while the store doesn't
    /// exist yet. Call once.
    public func events() -> AsyncStream<Void> {
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        state.withLock { $0.continuation = continuation }
        start()
        continuation.onTermination = { [weak self] _ in self?.stop() }
        return stream
    }

    /// Moves the watch from an ancestor to the store once the store exists
    /// (the CLI creates it on the first write).
    public func rewatchIfStoreAppeared() {
        let watched = state.withLock { $0.watched }
        guard let watched, watched != root, FileManager.default.fileExists(atPath: root) else { return }
        stop()
        start()
    }

    private func start() {
        var watched = root
        while !FileManager.default.fileExists(atPath: watched), watched != "/" {
            watched = (watched as NSString).deletingLastPathComponent
        }
        var context = FSEventStreamContext(version: 0, info: Unmanaged.passUnretained(self).toOpaque(),
                                           retain: nil, release: nil, copyDescription: nil)
        let flags = FSEventStreamCreateFlags(kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagUseCFTypes
            | kFSEventStreamCreateFlagWatchRoot)
        guard let stream = FSEventStreamCreate(kCFAllocatorDefault, StoreWatcher.callback, &context,
                                               [watched] as CFArray,
                                               FSEventStreamEventId(kFSEventStreamEventIdSinceNow),
                                               latency, flags) else { return }
        FSEventStreamSetDispatchQueue(stream, queue)
        FSEventStreamStart(stream)
        let box = Stream(ref: stream)
        let duty = StoreEventFilter.dutyRecords(in: dutyDirectory)
        state.withLock {
            $0.stream = box
            $0.watched = watched
            $0.dutyRecords = duty
        }
    }

    public func stop() {
        let box = state.withLock { s -> Stream? in
            defer { s.stream = nil }
            return s.stream
        }
        guard let stream = box?.ref else { return }
        FSEventStreamStop(stream)
        FSEventStreamInvalidate(stream)
        FSEventStreamRelease(stream)
    }

    private var dutyDirectory: String { (root as NSString).appendingPathComponent("duty") }

    fileprivate func handle(paths: [String], flags: [FSEventStreamEventFlags]) {
        let kinds = zip(paths, flags).map { StoreEventFilter.classify(path: $0, flags: $1, root: root) }
        var relevant = kinds.contains(.relevant)
        if kinds.contains(.duty) {
            let now = StoreEventFilter.dutyRecords(in: dutyDirectory)
            let changed = state.withLock { s -> Bool in
                defer { s.dutyRecords = now }
                return s.dutyRecords != now
            }
            relevant = relevant || changed
        }
        if relevant { _ = state.withLock { $0.continuation }?.yield() }
    }

    private static let callback: FSEventStreamCallback = { _, info, count, paths, flags, _ in
        guard let info else { return }
        let watcher = Unmanaged<StoreWatcher>.fromOpaque(info).takeUnretainedValue()
        let array = unsafeBitCast(paths, to: NSArray.self)
        let strings = (0..<count).compactMap { array[$0] as? String }
        let flagList = (0..<count).map { flags[$0] }
        watcher.handle(paths: strings, flags: flagList)
    }

    static func realPath(_ path: String) -> String {
        guard let r = realpath(path, nil) else {
            // Not there yet: resolve the existing part, keep the rest.
            let parent = (path as NSString).deletingLastPathComponent
            guard parent != path, !parent.isEmpty else { return path }
            return (realPath(parent) as NSString).appendingPathComponent((path as NSString).lastPathComponent)
        }
        defer { free(r) }
        return String(cString: r)
    }
}
