// The URLs inside a rendered thread and what a click on one does. The web
// view gets no file access to the store: attachments are linked and
// embedded as `agent-mail-file:///<thread>/<name>`, which the app serves
// (images only) from that thread's files/ directory and nothing else, and
// the card buttons (Open, Reply, Show in Finder) are
// `agent-mail-action:///<verb>/<thread>[/<item>]` links, never loaded, that
// the navigation delegate turns into app commands. Everything else a click
// can reach is classified here: http(s) and mailto go to the system, the
// rest is ignored.
import Foundation
import UniformTypeIdentifiers

public enum AttachmentScheme {
    public static let scheme = "agent-mail-file"

    /// Shown inline as images (thumbnails, `![](files/x.png)`); the only
    /// files the scheme handler serves.
    public static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "svg", "heic", "heif"]
    /// Rendered inline in a collapsible section.
    public static let markdownExtensions: Set<String> = ["md", "markdown"]

    public static func isImage(_ name: String) -> Bool {
        imageExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    public static func isMarkdown(_ name: String) -> Bool {
        markdownExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    /// `agent-mail-file:///<thread>/<name>`, or nil when either can't be a
    /// single path component.
    public static func url(thread: String, name: String) -> URL? {
        guard isPlainComponent(thread), isPlainComponent(name) else { return nil }
        return URL(string: "\(scheme):///\(percentEncode(thread))/\(percentEncode(name))")
    }

    /// The attachment name a URL names in thread `thread`, or nil: another
    /// scheme, a host, another thread, not exactly one name below the
    /// thread, `.` or `..`, or an encoded slash.
    public static func name(in url: URL, thread: String) -> String? {
        guard url.scheme?.lowercased() == scheme,
              let c = URLComponents(url: url, resolvingAgainstBaseURL: false),
              (c.host ?? "").isEmpty else { return nil }
        let parts = c.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].isEmpty,
              parts[1].removingPercentEncoding == thread,
              let name = parts[2].removingPercentEncoding, isPlainComponent(name) else { return nil }
        return name
    }

    /// The file a URL names inside `filesDirectory` (thread `thread`'s), or
    /// nil when it names none or would leave the directory, symlinks
    /// included. The file need not exist.
    public static func fileURL(for url: URL, thread: String, filesDirectory: URL) -> URL? {
        guard let name = name(in: url, thread: thread) else { return nil }
        return file(named: name, in: filesDirectory)
    }

    /// `name` inside `directory`, or nil when it would resolve elsewhere.
    public static func file(named name: String, in directory: URL) -> URL? {
        guard isPlainComponent(name) else { return nil }
        let base = directory.standardizedFileURL.resolvingSymlinksInPath()
        let file = base.appending(path: name).standardizedFileURL.resolvingSymlinksInPath()
        guard file.deletingLastPathComponent().path == base.path, file.lastPathComponent != ".." else { return nil }
        return file
    }

    /// The Content-Type the handler answers with.
    public static func mimeType(forName name: String) -> String {
        UTType(filenameExtension: (name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    static func isPlainComponent(_ s: String) -> Bool {
        !s.isEmpty && s != "." && s != ".." && !s.contains("/") && !s.contains("\0")
    }

    private static let unreserved = CharacterSet(charactersIn:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    static func percentEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? s
    }
}

/// What a navigation in the thread view does.
public enum ThreadLink: Equatable, Sendable {
    /// The document itself (`about:blank` from loadHTMLString).
    case loadDocument
    /// http(s) or mailto: the default browser or mail app.
    case openExternally(URL)
    /// An attachment by name: its default app.
    case openAttachment(String)
    case revealAttachment(String)
    /// A message file by name: the default app for Markdown.
    case openMessage(String)
    /// Focus the reply box, addressed for that message (nil: the thread).
    case reply(toMessage: String?)
    /// Anything else is cancelled.
    case ignore
}

public enum LinkPolicy {
    public static let actionScheme = "agent-mail-action"

    public enum Verb: String, Sendable {
        case open, reply, reveal
    }

    /// `agent-mail-action:///<verb>/<thread>[/<item>]`.
    public static func actionURL(_ verb: Verb, thread: String, item: String? = nil) -> URL? {
        guard AttachmentScheme.isPlainComponent(thread) else { return nil }
        var s = "\(actionScheme):///\(verb.rawValue)/\(AttachmentScheme.percentEncode(thread))"
        if let item {
            guard AttachmentScheme.isPlainComponent(item) else { return nil }
            s += "/" + AttachmentScheme.percentEncode(item)
        }
        return URL(string: s)
    }

    /// - Parameters:
    ///   - isLinkClick: the navigation is a click on a link (not a load,
    ///     redirect or form).
    ///   - thread: the thread on screen; links into any other are ignored.
    public static func classify(_ url: URL?, isLinkClick: Bool, thread: String?) -> ThreadLink {
        guard let url else { return .ignore }
        guard isLinkClick else { return url.absoluteString == "about:blank" ? .loadDocument : .ignore }
        switch url.scheme?.lowercased() {
        case "http", "https", "mailto":
            return .openExternally(url)
        case AttachmentScheme.scheme:
            guard let thread, let name = AttachmentScheme.name(in: url, thread: thread) else { return .ignore }
            return .openAttachment(name)
        case actionScheme:
            guard let thread else { return .ignore }
            return action(url, thread: thread)
        default:
            return .ignore
        }
    }

    private static func action(_ url: URL, thread: String) -> ThreadLink {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), (c.host ?? "").isEmpty else {
            return .ignore
        }
        let parts = c.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false)
            .map { $0.removingPercentEncoding }
        guard parts.count == 3 || parts.count == 4, parts[0] == "", parts[2] == thread,
              let verb = parts[1].flatMap(Verb.init(rawValue:)) else { return .ignore }
        let item = parts.count == 4 ? parts[3] : nil
        if parts.count == 4 {
            guard let item, AttachmentScheme.isPlainComponent(item) else { return .ignore }
        }
        switch (verb, item) {
        case let (.open, item?): return .openMessage(item)
        case let (.reveal, item?): return .revealAttachment(item)
        case let (.reply, item): return .reply(toMessage: item)
        default: return .ignore
        }
    }

    /// Whether a file may be handed to its default app: an executable,
    /// script or app is shown in Finder instead, so a click never runs one.
    public static func isSafeToOpen(_ file: URL) -> Bool {
        let type = UTType(filenameExtension: file.pathExtension) ?? .data
        let risky: [UTType] = [.executable, .application, .script, .unixExecutable, .shellScript, .applicationBundle,
                               .package, .bundle]
        if risky.contains(where: { type.conforms(to: $0) }) { return false }
        let attrs = try? FileManager.default.attributesOfItem(atPath: file.path)
        if let perms = attrs?[.posixPermissions] as? NSNumber, perms.intValue & 0o111 != 0 { return false }
        return true
    }
}
