// The sound a notification plays, one setting per kind (mail to the user,
// mail between agents): the system's default, none, one of the app's own
// (Resources/Sounds, made by scripts/sounds.bb) or a system alert sound from
// /System/Library/Sounds. Stored in defaults as a string such as "default",
// "none", "bundled:Envelope" or "system:Glass".
import Foundation
import UserNotifications

enum NotificationSoundChoice: Hashable {
    case systemDefault
    case none
    /// One of `bundledNames`, Contents/Resources/<name>.caf.
    case bundled(String)
    /// A file <name>.aiff in /System/Library/Sounds.
    case system(String)

    /// The app's own sounds, in the order the popup lists them.
    static let bundledNames = ["Envelope", "Prompt", "Cursor"]
    static let systemSoundsDirectory = URL(filePath: "/System/Library/Sounds")

    /// Total: a string it doesn't know is the default.
    init(rawValue: String) {
        switch rawValue {
        case "none": self = .none
        case _ where rawValue.hasPrefix("bundled:") && rawValue.count > 8: self = .bundled(String(rawValue.dropFirst(8)))
        case _ where rawValue.hasPrefix("system:") && rawValue.count > 7: self = .system(String(rawValue.dropFirst(7)))
        default: self = .systemDefault
        }
    }

    var rawValue: String {
        switch self {
        case .systemDefault: "default"
        case .none: "none"
        case let .bundled(name): "bundled:\(name)"
        case let .system(name): "system:\(name)"
        }
    }

    var title: String {
        switch self {
        case .systemDefault: "Default"
        case .none: "None"
        case let .bundled(name), let .system(name): name
        }
    }

    /// What the popup lists, in sections a separator divides: Default, None
    /// and the app's own sounds; then the system's, sorted.
    static func sections(systemSounds directory: URL = systemSoundsDirectory) -> [[NotificationSoundChoice]] {
        let system = systemSoundNames(in: directory).map(NotificationSoundChoice.system)
        return [[.systemDefault, .none] + bundledNames.map(NotificationSoundChoice.bundled), system].filter { !$0.isEmpty }
    }

    /// The names of the .aiff files in `directory`, sorted as Finder does.
    static func systemSoundNames(in directory: URL = systemSoundsDirectory) -> [String] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension.lowercased() == "aiff" }
            .map { $0.deletingPathExtension().lastPathComponent }
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    /// The notification's sound; nil plays none. A file the system can't
    /// find plays the default.
    var unSound: UNNotificationSound? {
        switch self {
        case .systemDefault: .default
        case .none: nil
        case let .bundled(name): UNNotificationSound(named: UNNotificationSoundName("\(name).caf"))
        case let .system(name): UNNotificationSound(named: UNNotificationSoundName("\(name).aiff"))
        }
    }

    /// The file to play in Settings; nil for Default and None, and for a
    /// sound that isn't there.
    var previewURL: URL? {
        switch self {
        case .systemDefault, .none:
            return nil
        case let .bundled(name):
            return Bundle.main.url(forResource: name, withExtension: "caf")
        case let .system(name):
            let url = Self.systemSoundsDirectory.appending(path: "\(name).aiff")
            return FileManager.default.fileExists(atPath: url.path) ? url : nil
        }
    }
}
