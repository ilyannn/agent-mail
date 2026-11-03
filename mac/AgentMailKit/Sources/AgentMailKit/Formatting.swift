// Short human forms for the thread list: ages like the CLI prints them
// (core/duration: 45s, 12m, 3h, 2d) and names that are safe to address.
import Foundation

public enum Formatting {
    /// Whole seconds as one rounded-down unit.
    public static func age(seconds s: Int) -> String {
        let s = max(0, s)
        if s < 60 { return "\(s)s" }
        if s < 3600 { return "\(s / 60)m" }
        if s < 86400 { return "\(s / 3600)h" }
        return "\(s / 86400)d"
    }

    /// Age of `date` as of `now`.
    public static func age(of date: Date?, now: Date) -> String {
        guard let date else { return "" }
        return age(seconds: Int(now.timeIntervalSince(date)))
    }

    /// The same words spelled out, for VoiceOver.
    public static func spokenAge(seconds s: Int) -> String {
        let s = max(0, s)
        func unit(_ n: Int, _ word: String) -> String { "\(n) \(word)\(n == 1 ? "" : "s")" }
        if s < 60 { return unit(s, "second") }
        if s < 3600 { return unit(s / 60, "minute") }
        if s < 86400 { return unit(s / 3600, "hour") }
        return unit(s / 86400, "day")
    }

    /// A participant name the CLI accepts: non-empty, no whitespace or path
    /// separators (core/valid-name?).
    public static func isValidName(_ s: String) -> Bool {
        !s.isEmpty && !s.contains { $0.isWhitespace || $0 == "/" || $0 == "\\" }
    }

    /// A name `me` can mail: valid, and not `me` (the CLI refuses that).
    public static func isValidRecipient(_ s: String, me: String) -> Bool {
        isValidName(s) && s != me
    }
}
