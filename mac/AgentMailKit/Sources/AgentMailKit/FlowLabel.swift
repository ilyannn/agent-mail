// A thread's flow (core/flow: who it passed through, then whose turn it is)
// as one row of the thread list shows it: at most four names, so a long
// back-and-forth keeps its start and its last two parties (A → … → B → A).
import Foundation

public struct FlowLabel: Equatable, Sendable {
    /// One piece of the shortened flow.
    public enum Step: Equatable, Sendable {
        case name(String)
        /// Names left out of the middle, and how many.
        case gap(Int)
    }

    /// Longer flows are cut to the first name, a gap and the last two.
    public static let maxNames = 4
    public static let arrow = " → "
    public static let ellipsis = "…"

    /// The whole flow.
    public let names: [String]
    /// What the row shows; the last step is the party whose turn it is
    /// (for an open thread).
    public let steps: [Step]

    public init(_ names: [String]) {
        self.names = names
        let n = names.count
        steps = n <= Self.maxNames ? names.map(Step.name)
            : [.name(names[0]), .gap(n - 3), .name(names[n - 2]), .name(names[n - 1])]
    }

    public var isShortened: Bool { names.count > Self.maxNames }

    /// The steps as text: names joined by arrows, an ellipsis for the gap.
    public var text: String {
        steps.map { step in
            switch step {
            case let .name(name): name
            case .gap: Self.ellipsis
            }
        }.joined(separator: Self.arrow)
    }

    /// Every name, joined by arrows (for a tooltip when the row is shortened).
    public var fullText: String { names.joined(separator: Self.arrow) }

    /// The steps in words, for VoiceOver: "A to B, back to A", "A to B, then
    /// C", "A, then 3 others, then D, back to A".
    public var spoken: String {
        guard let first = names.first else { return "" }
        var parts = [first]
        var next = 1  // index in `names` of the next step's first name
        for step in steps.dropFirst() {
            switch step {
            case let .gap(count):
                parts.append("then \(count) other\(count == 1 ? "" : "s")")
                next += count
            case let .name(name):
                if next == 1 {
                    parts[0] += " to \(name)"
                } else {
                    parts.append(names[..<next].contains(name) ? "back to \(name)" : "then \(name)")
                }
                next += 1
            }
        }
        return parts.joined(separator: ", ")
    }
}
