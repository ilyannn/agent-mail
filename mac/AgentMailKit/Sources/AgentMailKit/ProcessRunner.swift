// Running a command with Foundation's Process: stdin from Data, stdout and
// stderr drained concurrently (so a chatty child never blocks on a full
// pipe), a timeout, and cancellation that terminates the child.
import Foundation
import Synchronization

public struct ProcessResult: Sendable, Equatable {
    public var status: Int32
    public var stdout: Data
    public var stderr: Data
    public var duration: Duration

    public init(status: Int32, stdout: Data, stderr: Data, duration: Duration) {
        self.status = status
        self.stdout = stdout
        self.stderr = stderr
        self.duration = duration
    }

    public var stdoutString: String { String(decoding: stdout, as: UTF8.self) }
    public var stderrString: String { String(decoding: stderr, as: UTF8.self) }
}

public enum ProcessRunError: Error, Equatable, Sendable {
    case launch(String)
    case timedOut
}

/// The seam the CLI client runs commands through; tests substitute a fake.
public protocol CommandRunning: Sendable {
    func run(_ executable: URL, arguments: [String], environment: [String: String], directory: URL,
             input: Data?, timeout: Duration) async throws -> ProcessResult
}

public struct ProcessRunner: CommandRunning {
    public init() {
        // Writing a body to a child that already exited raises SIGPIPE, which
        // would kill the app; ignored, the write fails with EPIPE instead.
        signal(SIGPIPE, SIG_IGN)
    }

    public func run(_ executable: URL, arguments: [String], environment: [String: String], directory: URL,
                    input: Data?, timeout: Duration) async throws -> ProcessResult {
        let execution = Execution(executable: executable, arguments: arguments, environment: environment,
                                  directory: directory, input: input, timeout: timeout)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<ProcessResult, any Error>) in
                execution.start { cont.resume(with: $0) }
            }
        } onCancel: {
            execution.terminate()
        }
    }
}

/// One run of one process. Mutable state lives behind a mutex; the work runs
/// on global queues so nothing blocks a Swift concurrency thread.
private final class Execution: Sendable {
    private struct State {
        var process: Process?
        var stdout = Data()
        var stderr = Data()
        var timedOut = false
        var cancelled = false
    }

    private let executable: URL
    private let arguments: [String]
    private let environment: [String: String]
    private let directory: URL
    private let input: Data?
    private let timeout: Duration
    private let state = Mutex(State())

    init(executable: URL, arguments: [String], environment: [String: String], directory: URL, input: Data?,
         timeout: Duration) {
        self.executable = executable
        self.arguments = arguments
        self.environment = environment
        self.directory = directory
        self.input = input
        self.timeout = timeout
    }

    func start(_ done: @escaping @Sendable (Result<ProcessResult, any Error>) -> Void) {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        process.environment = environment
        process.currentDirectoryURL = directory
        let outPipe = Pipe(), errPipe = Pipe(), inPipe = Pipe()
        process.standardOutput = outPipe
        process.standardError = errPipe
        process.standardInput = inPipe

        let group = DispatchGroup()
        group.enter()
        process.terminationHandler = { _ in group.leave() }

        let began = ContinuousClock.now
        let alreadyCancelled = state.withLock { s -> Bool in
            s.process = process
            return s.cancelled
        }
        if alreadyCancelled {
            done(.failure(CancellationError()))
            return
        }
        do {
            try process.run()
        } catch {
            done(.failure(ProcessRunError.launch(error.localizedDescription)))
            return
        }

        let queue = DispatchQueue.global(qos: .userInitiated)
        for (pipe, isOut) in [(outPipe, true), (errPipe, false)] {
            group.enter()
            queue.async { [self] in
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                state.withLock { if isOut { $0.stdout = data } else { $0.stderr = data } }
                group.leave()
            }
        }
        let input = self.input
        queue.async {
            let w = inPipe.fileHandleForWriting
            if let input, !input.isEmpty { try? w.write(contentsOf: input) }
            try? w.close()
        }

        let deadline = DispatchWorkItem { [weak self] in self?.expire() }
        let c = timeout.components
        let seconds = Double(c.seconds) + Double(c.attoseconds) / 1e18
        queue.asyncAfter(deadline: .now() + seconds, execute: deadline)

        group.notify(queue: queue) { [self] in
            deadline.cancel()
            let (out, err, timedOut, cancelled) = state.withLock { ($0.stdout, $0.stderr, $0.timedOut, $0.cancelled) }
            if cancelled { done(.failure(CancellationError())); return }
            if timedOut { done(.failure(ProcessRunError.timedOut)); return }
            done(.success(ProcessResult(status: process.terminationStatus, stdout: out, stderr: err,
                                        duration: ContinuousClock.now - began)))
        }
    }

    private func expire() {
        let p = state.withLock { s -> Process? in
            s.timedOut = true
            return s.process
        }
        kill(p)
    }

    func terminate() {
        let p = state.withLock { s -> Process? in
            s.cancelled = true
            return s.process
        }
        kill(p)
    }

    /// SIGTERM, then SIGKILL if it's still running a second later.
    private func kill(_ p: Process?) {
        guard let p, p.isRunning else { return }
        p.terminate()
        let pid = p.processIdentifier
        DispatchQueue.global().asyncAfter(deadline: .now() + 1) {
            if p.isRunning { Darwin.kill(pid, SIGKILL) }
        }
    }
}
