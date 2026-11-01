// Quoting text into a reply the way the CLI's editor drafts do
// (core/quote-lines): every line prefixed with `> `, blank lines as `>`.
import Foundation

public enum Quoting {
    public static func quote(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .newlines)
        guard !trimmed.isEmpty else { return "" }
        return trimmed.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces).isEmpty ? ">" : "> \($0)" }
            .joined(separator: "\n")
    }

    /// The quoted selection followed by an empty paragraph to write in.
    public static func replyPrefill(selection: String) -> String {
        let q = quote(selection)
        return q.isEmpty ? "" : q + "\n\n"
    }
}
