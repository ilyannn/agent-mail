// Fixtures captured from real `agent-mail status --json` and `read --json`
// output against a scratch store (repos `infra` and `website`, and `user`;
// the -forward ones also `docs`, with two forwards of a closed thread).
// status-directory has the agent directory (docs untrusted, gone's folder
// removed, notes off call, website's summary borrowed from its README) and
// a hand-written serve state: infra's #1 and website's #2 held by sessions,
// website's waiting on the user, infra unable to land and with an unlanded
// session; old-repo, the owner of #4, was removed from the directory. Its
// agents' running sessions are hand-written too: infra's #1 busy beside an
// idle interactive one, a busy one in notes (off call), website's waiting.
import Foundation

enum Fixture {
    static func data(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures") else {
            throw CocoaError(.fileNoSuchFile, userInfo: [NSFilePathErrorKey: "Fixtures/\(name).json"])
        }
        return try Data(contentsOf: url)
    }

    static func date(_ s: String) -> Date {
        guard let d = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(s) else {
            fatalError("bad fixture date \(s)")
        }
        return d
    }
}
