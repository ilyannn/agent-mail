// Fixtures captured from real `agent-mail status --json` and `read --json`
// output against a scratch store (repos `infra` and `website`, and `user`).
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
