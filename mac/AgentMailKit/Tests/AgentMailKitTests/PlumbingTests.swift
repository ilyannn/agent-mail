import CoreServices
import Foundation
import Testing
@testable import AgentMailKit

@Suite struct StoreEventFilterTests {
    let root = "/private/tmp/store"
    let modified = FSEventStreamEventFlags(kFSEventStreamEventFlagItemModified | kFSEventStreamEventFlagItemIsFile)
    let created = FSEventStreamEventFlags(kFSEventStreamEventFlagItemCreated | kFSEventStreamEventFlagItemIsFile)

    func kind(_ rel: String, _ flags: FSEventStreamEventFlags) -> StoreEventFilter.Kind {
        StoreEventFilter.classify(path: root + "/" + rel, flags: flags, root: root)
    }

    @Test func messagesAndSeenMarkersCount() {
        #expect(kind("threads/t1/msgs/2026-10-31T230503.415Z__infra__872a.md", created) == .relevant)
        #expect(kind("threads/t1/thread.md", modified) == .relevant)
        #expect(kind("seen/user.edn", modified) == .relevant)
    }

    @Test func theAgentDirectoryCounts() {
        // `agents add`, `oncall add`/`remove` and a description change the
        // sidebar.
        #expect(kind("agents/infra.md", created) == .relevant)
        #expect(kind("agents/infra.md", modified) == .relevant)
        #expect(kind("agents", created) == .relevant, "the folder moved in on the first write")
        #expect(kind("agents/.infra.md.tmp", created) == .ignored)
        #expect(kind(".agents.4242.tmp/infra.md", created) == .ignored)
    }

    @Test func gitLocksDraftsAndTempFilesDont() {
        #expect(kind(".git/index", modified) == .ignored)
        #expect(kind(".git/objects/ab/cdef", created) == .ignored)
        #expect(kind(".lock", created) == .ignored)
        #expect(kind("drafts/t1.md", created) == .ignored)
        #expect(kind("view/t1.md", created) == .ignored)
        #expect(kind("threads/t1/msgs/.x.md.tmp", created) == .ignored)
        #expect(StoreEventFilter.classify(path: "/private/tmp/storex/threads/a", flags: created, root: root) == .ignored)
    }

    @Test func dutyIsDecidedByTheSetOfRecords() {
        // FSEvents flags every heartbeat rewrite as Created, so flags can't tell.
        #expect(kind("duty/website__s1.edn", modified) == .duty)
        #expect(kind("duty/website__s1.edn", created) == .duty)
    }

    @Test func droppedEventsForceARefresh() {
        let flags = FSEventStreamEventFlags(kFSEventStreamEventFlagMustScanSubDirs)
        #expect(StoreEventFilter.classify(path: "/elsewhere", flags: flags, root: root) == .relevant)
    }
}

@Suite struct RefreshSchedulerTests {
    @MainActor @Test func requestsWhileRunningCoalesceIntoOneFollowUp() async {
        var started = 0
        var gate: CheckedContinuation<Void, Never>?
        let scheduler = RefreshScheduler {
            started += 1
            if started == 1 { await withCheckedContinuation { gate = $0 } }
        }
        let first = Task { await scheduler.request() }
        while gate == nil { await Task.yield() }
        #expect(scheduler.isRunning)
        await scheduler.request()
        await scheduler.request()
        await scheduler.request()
        gate?.resume()
        await first.value
        #expect(started == 2)
        #expect(scheduler.runs == 2)
        #expect(!scheduler.isRunning)
        await scheduler.request()
        #expect(started == 3)
    }
}

/// A stub CLI: a shell script that records its arguments, environment and
/// stdin, and answers from canned output.
struct StubCLI {
    let dir: URL
    var executable: URL { dir.appending(path: "agent-mail") }
    var log: URL { dir.appending(path: "calls.log") }

    init(script: String) throws {
        dir = FileManager.default.temporaryDirectory.appending(path: "stub-cli-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try ("#!/bin/sh\nLOG=\"\(dir.path)/calls.log\"\n" + script).write(to: executable, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: executable.path)
    }

    func client(timeout: Duration = .seconds(10)) -> CLIClient {
        CLIClient(executable: executable,
                  environment: CLIClient.environment(base: ["PATH": "/usr/bin:/bin", "CLAUDE_CODE_CHILD_SESSION": "1"],
                                                     store: URL(filePath: "/tmp/fixture-store")),
                  directory: FileManager.default.homeDirectoryForCurrentUser, timeout: timeout)
    }

    func calls() throws -> String { try String(contentsOf: log, encoding: .utf8) }
    func remove() { try? FileManager.default.removeItem(at: dir) }
}

@Suite struct CLIClientTests {
    @Test func statusRunsAsUserAgainstTheStore() async throws {
        let fixture = Bundle.module.url(forResource: "status", withExtension: "json", subdirectory: "Fixtures")!
        let stub = try StubCLI(script: """
            echo "args: $*" >> "$LOG"
            echo "as=$AGENT_MAIL_AS dir=$AGENT_MAIL_DIR quiet=$AGENT_MAIL_NO_NOTIFY child=${CLAUDE_CODE_CHILD_SESSION:-unset} pwd=$PWD" >> "$LOG"
            cat "\(fixture.path)"
            """)
        defer { stub.remove() }
        let r = try await stub.client().status()
        #expect(r.value.threads.count == 4)
        let log = try stub.calls()
        #expect(log.contains("args: status --json"))
        #expect(log.contains("as=user dir=/tmp/fixture-store quiet=1 child=unset"))
        #expect(log.contains("pwd=\(FileManager.default.homeDirectoryForCurrentUser.path)"))
    }

    @Test func writesPassTheBodyOnStdin() async throws {
        let stub = try StubCLI(script: """
            echo "args: $*" >> "$LOG"
            echo "body: $(cat)" >> "$LOG"
            echo "2026-10-31-new-thread"
            """)
        defer { stub.remove() }
        let client = stub.client()
        try await client.reply(threadID: "t1", to: "infra", body: "--not an option\nline two")
        let id = try await client.send(to: "website", subject: "Hello", body: "Body")
        #expect(id == "2026-10-31-new-thread")
        try await client.close(threadID: "t1")
        let log = try stub.calls()
        #expect(log.contains("args: reply t1 --to infra\nbody: --not an option\nline two"))
        #expect(log.contains("args: send --to website --subject Hello\nbody: Body"))
        #expect(log.contains("args: close t1\nbody: \n"))
    }

    @Test func forwardPassesTheNoteAsOneArgument() async throws {
        let stub = try StubCLI(script: """
            for a in "$@"; do echo "arg: $a" >> "$LOG"; done
            echo "body: $(cat)" >> "$LOG"
            echo "2026-11-03-fwd-deploy"
            """)
        defer { stub.remove() }
        let client = stub.client()
        let id = try await client.forward(threadID: "t1", message: "a.md", to: "docs", subject: "Fwd: Deploy",
                                          note: "--see below\nline two")
        #expect(id == "2026-11-03-fwd-deploy")
        try await client.forward(threadID: "t1", to: "infra")
        let log = try stub.calls()
        #expect(log.contains("""
            arg: forward
            arg: t1
            arg: --to
            arg: docs
            arg: --msg
            arg: a.md
            arg: --subject
            arg: Fwd: Deploy
            arg: --
            arg: --see below
            line two
            body: \n
            """))
        #expect(log.hasSuffix("arg: forward\narg: t1\narg: --to\narg: infra\nbody: \n"))
    }

    @Test func forwardArguments() {
        func args(message: String? = nil, subject: String? = nil, note: String = "") -> [String] {
            CLIClient.forwardArguments(threadID: "t", message: message, to: "docs", subject: subject, note: note)
        }
        #expect(args() == ["forward", "t", "--to", "docs"])
        #expect(args(subject: "  ", note: " \n ") == ["forward", "t", "--to", "docs"], "blank: the CLI's defaults")
        #expect(args(message: "m.md", subject: "S", note: "hi") == ["forward", "t", "--to", "docs", "--msg", "m.md",
                                                                    "--subject", "S", "--", "hi"])
    }

    @Test func theDirectoryIsChangedThroughTheCLI() async throws {
        let stub = try StubCLI(script: """
            for a in "$@"; do echo "arg: $a" >> "$LOG"; done
            echo "--" >> "$LOG"
            if [ "$1" = agents ]; then
              echo "my-repo is in the agent directory, on call (/x/my repo)"
              echo "Say what it owns and what to ask it for: \\`agent-mail agents describe my-repo < about.md\\`"
            else
              echo "infra is on call (/x/infra)"
            fi
            """)
        defer { stub.remove() }
        let client = stub.client()
        #expect(try await client.addAgent(directory: "/x/my repo") == "my-repo")
        try await client.setOnCall("infra", true)
        try await client.setOnCall("infra", false)
        #expect(try stub.calls() == """
            arg: agents
            arg: add
            arg: /x/my repo
            --
            arg: oncall
            arg: add
            arg: infra
            --
            arg: oncall
            arg: remove
            arg: infra
            --

            """)
        #expect(CLIClient.addedAgentName("infra is in the agent directory, on call (/x/infra)\n") == "infra")
        #expect(CLIClient.addedAgentName("something else\n") == nil)
        #expect(CLIClient.addedAgentName("") == nil)
    }

    @Test func aRefusedFolderSaysWhy() async throws {
        let stub = try StubCLI(script: "echo 'agent-mail: /x/infra is already in the agent directory, as infra' >&2\nexit 1\n")
        defer { stub.remove() }
        do {
            try await stub.client().addAgent(directory: "/x/infra")
            Issue.record("expected a refusal")
        } catch {
            #expect(error.errorDescription == "agent-mail agents add failed: /x/infra is already in the agent directory, as infra")
        }
    }

    @Test func nonZeroExitIsAnError() async throws {
        let stub = try StubCLI(script: "echo 'agent-mail: no thread #9' >&2\nexit 1\n")
        defer { stub.remove() }
        await #expect(throws: AgentMailError.exit(command: "reply", code: 1, stderr: "agent-mail: no thread #9\n")) {
            try await stub.client().reply(threadID: "9", to: "infra", body: "x")
        }
    }

    @Test func hangingCLITimesOut() async throws {
        let stub = try StubCLI(script: "sleep 30\n")
        defer { stub.remove() }
        let began = ContinuousClock.now
        await #expect(throws: AgentMailError.timedOut(command: "status", seconds: 1)) {
            try await stub.client(timeout: .seconds(1)).status()
        }
        #expect(ContinuousClock.now - began < .seconds(5))
    }

    @Test func largeOutputDoesNotDeadlock() async throws {
        // 1 MB on stdout and stderr each, more than a pipe buffer holds.
        let stub = try StubCLI(script: "head -c 1048576 /dev/zero | tr '\\\\0' x; head -c 1048576 /dev/zero >&2; exit 3\n")
        defer { stub.remove() }
        do {
            try await stub.client().close(threadID: "t")
            Issue.record("expected exit 3")
        } catch {
            guard case let .exit(_, code, stderr) = error else { Issue.record("\(error)"); return }
            #expect(code == 3)
            #expect(stderr.utf8.count == 1_048_576)
        }
    }

    @Test func missingOverrideIsReported() async {
        let locator = CLILocator(override: "/nonexistent/agent-mail")
        await #expect(throws: AgentMailError.cliNotFound(searched: ["/nonexistent/agent-mail (from Settings)"])) {
            try await locator.locate()
        }
    }

    @Test func overrideIsUsedWhenExecutable() async throws {
        let stub = try StubCLI(script: "exit 0\n")
        defer { stub.remove() }
        #expect(try await CLILocator(override: stub.executable.path).locate() == stub.executable)
    }

    @Test func loginShellOutputIsParsed() {
        #expect(CLILocator.lastAbsolutePath(in: "Welcome!\n/opt/bin/agent-mail\n") == "/opt/bin/agent-mail")
        #expect(CLILocator.lastAbsolutePath(in: "agent-mail not found\n") == nil)
    }
}

@Suite(.serialized) struct StoreWatcherTests {
    actor Counter {
        var value = 0
        func increment() { value += 1 }
    }

    /// Waits until the count moves past `baseline`, up to 3 s.
    func waitPast(_ baseline: Int, _ counter: Counter) async throws -> Bool {
        var waited = Duration.zero
        while await counter.value == baseline, waited < .seconds(3) {
            try await Task.sleep(for: .milliseconds(50))
            waited += .milliseconds(50)
        }
        return await counter.value > baseline
    }

    @Test func dutyHeartbeatsDontYieldButNewRecordsDo() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "watch-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let duty = root.appending(path: "duty")
        try FileManager.default.createDirectory(at: duty, withIntermediateDirectories: true)
        let record = duty.appending(path: "website__s1.edn")
        try "{:state \"waiting\"}".write(to: record, atomically: false, encoding: .utf8)

        let watcher = StoreWatcher(root: root, latency: 0.1)
        let counter = Counter()
        let stream = watcher.events()
        let consumer = Task { for await _ in stream { await counter.increment() } }
        defer {
            consumer.cancel()
            watcher.stop()
        }
        try await Task.sleep(for: .milliseconds(800))
        let baseline = await counter.value

        for _ in 0..<3 {
            try "{:state \"waiting\" :heartbeat 1}".write(to: record, atomically: false, encoding: .utf8)
            try await Task.sleep(for: .milliseconds(250))
        }
        try await Task.sleep(for: .milliseconds(500))
        #expect(await counter.value == baseline, "rewriting a record is a heartbeat")

        try "{}".write(to: duty.appending(path: "infra__s2.edn"), atomically: false, encoding: .utf8)
        #expect(try await waitPast(baseline, counter), "a new record yields")
        let afterNew = await counter.value
        try FileManager.default.removeItem(at: record)
        #expect(try await waitPast(afterNew, counter), "a record going away yields")
    }

    @Test func messageWritesYieldAndGitWritesDont() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "watch-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let msgs = root.appending(path: "threads/t1/msgs")
        let git = root.appending(path: ".git/objects")
        try FileManager.default.createDirectory(at: msgs, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: git, withIntermediateDirectories: true)

        let watcher = StoreWatcher(root: root, latency: 0.1)
        let counter = Counter()
        let stream = watcher.events()
        let consumer = Task {
            for await _ in stream { await counter.increment() }
        }
        defer {
            consumer.cancel()
            watcher.stop()
        }
        // FSEvents may still report the directories made just before the
        // stream started; count from a quiet baseline.
        try await Task.sleep(for: .milliseconds(800))
        let baseline = await counter.value

        try "x".write(to: git.appending(path: "obj"), atomically: false, encoding: .utf8)
        try await Task.sleep(for: .milliseconds(800))
        #expect(await counter.value == baseline, "git writes are ignored")

        try "y".write(to: msgs.appending(path: "a.md"), atomically: false, encoding: .utf8)
        var waited = Duration.zero
        while await counter.value == baseline, waited < .seconds(3) {
            try await Task.sleep(for: .milliseconds(50))
            waited += .milliseconds(50)
        }
        #expect(await counter.value > baseline, "a new message file yields")
    }
}
