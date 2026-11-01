// One refresh at a time: a request while one runs schedules exactly one
// follow-up, however many requests arrive meanwhile. Runs on the main actor,
// where the store that applies the results lives.
import Foundation

@MainActor
public final class RefreshScheduler {
    private let work: @MainActor () async -> Void
    public private(set) var isRunning = false
    private var pending = false
    /// Completed runs, for tests and timing logs.
    public private(set) var runs = 0

    public init(work: @escaping @MainActor () async -> Void) {
        self.work = work
    }

    /// Requests a refresh; returns once the run it started (and any
    /// follow-up) is over, or at once when it only scheduled a follow-up.
    public func request() async {
        if isRunning {
            pending = true
            return
        }
        isRunning = true
        repeat {
            pending = false
            await work()
            runs += 1
        } while pending
        isRunning = false
    }
}
