// Decoding the CLI's JSON: ISO-8601 UTC dates with milliseconds (or without),
// and a schema check before anything else so a newer CLI gets a clear error
// instead of a decoding failure.
import Foundation

public enum AgentMailJSON {
    public static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let c = try decoder.singleValueContainer()
            let s = try c.decode(String.self)
            if let date = parseDate(s) { return date }
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "not an ISO-8601 date: \(s)")
        }
        return d
    }

    /// `2026-10-31T23:05:03.205Z` or `2026-10-31T23:05:03Z`.
    public static func parseDate(_ s: String) -> Date? {
        if let d = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(s) { return d }
        return try? Date.ISO8601FormatStyle().parse(s)
    }

    private struct SchemaProbe: Decodable { var schema: Int? }

    public static func decodeStatus(_ data: Data) throws(AgentMailError) -> MailStatus {
        let probe: SchemaProbe
        do { probe = try JSONDecoder().decode(SchemaProbe.self, from: data) } catch {
            throw .decode(what: "status --json", detail: describe(error, data: data))
        }
        guard probe.schema == MailStatus.supportedSchema else {
            throw .schemaMismatch(found: probe.schema, supported: MailStatus.supportedSchema)
        }
        do { return try decoder().decode(MailStatus.self, from: data) } catch {
            throw .decode(what: "status --json", detail: describe(error, data: data))
        }
    }

    public static func decodeThread(_ data: Data) throws(AgentMailError) -> ThreadDetail {
        do { return try decoder().decode(ThreadDetail.self, from: data) } catch {
            throw .decode(what: "read --json", detail: describe(error, data: data))
        }
    }

    static func describe(_ error: any Error, data: Data) -> String {
        let head = String(decoding: data.prefix(200), as: UTF8.self)
        switch error {
        case let DecodingError.keyNotFound(key, ctx):
            return "missing \(key.stringValue) at \(path(ctx))"
        case let DecodingError.typeMismatch(_, ctx), let DecodingError.valueNotFound(_, ctx):
            return "\(ctx.debugDescription) at \(path(ctx))"
        case let DecodingError.dataCorrupted(ctx):
            return ctx.codingPath.isEmpty ? "not JSON: \(head)" : "\(ctx.debugDescription) at \(path(ctx))"
        default:
            return "\(error.localizedDescription): \(head)"
        }
    }

    private static func path(_ ctx: DecodingError.Context) -> String {
        ctx.codingPath.map { $0.intValue.map { "[\($0)]" } ?? $0.stringValue }.joined(separator: ".")
    }
}
