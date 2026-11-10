// Files waiting to go with a reply, a closing note or a new thread: the
// list the reply pane shows, and the name the app gives an image pasted
// without a file. The CLI copies each file into the thread when the message
// is sent (`--attach`), so the list holds only where the files are now.
import Foundation

public enum Attaching {
    /// `list`, then the files of `new` it doesn't hold yet, in their order.
    /// Anything that isn't a file URL (a web link) is left out.
    public static func adding(_ new: [URL], to list: [URL]) -> [URL] {
        var out = list
        var held = Set(list.map(\.standardizedFileURL.path))
        for url in new where url.isFileURL {
            let file = url.standardizedFileURL
            if held.insert(file.path).inserted { out.append(file) }
        }
        return out
    }

    /// The name for an image pasted without a file, from its time:
    /// `pasted-2026-11-10-204412.png`, with `-2`, `-3`… when a name in
    /// `taken` has it already.
    public static func pastedName(at date: Date, extension ext: String, taken: Set<String> = [],
                                  timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        func two(_ n: Int?) -> String { String(format: "%02d", n ?? 0) }
        let stem = "pasted-\(c.year ?? 0)-\(two(c.month))-\(two(c.day))-\(two(c.hour))\(two(c.minute))\(two(c.second))"
        var name = "\(stem).\(ext)"
        var n = 2
        while taken.contains(name) {
            name = "\(stem)-\(n).\(ext)"
            n += 1
        }
        return name
    }

    /// "1 attachment", "3 attachments".
    public static func count(_ files: [URL]) -> String {
        files.count == 1 ? "1 attachment" : "\(files.count) attachments"
    }
}
