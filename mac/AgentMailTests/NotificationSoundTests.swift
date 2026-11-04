// The notification sound choices: what each one plays, what the popup
// lists, and that the app's own sounds are in the bundle where
// UNNotificationSound(named:) looks for them.
import Foundation
import Testing
import UserNotifications
@testable import AgentMail

@MainActor @Suite struct NotificationSoundTests {
    @Test func noneIsSilentAndTheOthersPlay() {
        #expect(NotificationSoundChoice.none.unSound == nil)
        #expect(NotificationSoundChoice.systemDefault.unSound != nil)
        #expect(NotificationSoundChoice.bundled("Envelope").unSound != nil)
        #expect(NotificationSoundChoice.system("Glass").unSound != nil)
        #expect(NotificationSoundChoice.none.previewURL == nil)
        #expect(NotificationSoundChoice.systemDefault.previewURL == nil)
    }

    @Test func titlesAreTheSoundNames() {
        #expect(NotificationSoundChoice.systemDefault.title == "Default")
        #expect(NotificationSoundChoice.none.title == "None")
        #expect(NotificationSoundChoice.bundled("Prompt").title == "Prompt")
        #expect(NotificationSoundChoice.system("Glass").title == "Glass")
    }

    /// The test host is the app, so this is the app bundle's own copy:
    /// directly in Contents/Resources, not a subfolder.
    @Test func theAppsOwnSoundsAreInTheBundle() throws {
        #expect(NotificationSoundChoice.bundledNames == ["Envelope", "Prompt", "Cursor"])
        let resources = try #require(Bundle.main.resourceURL)
        for name in NotificationSoundChoice.bundledNames {
            let url = try #require(NotificationSoundChoice.bundled(name).previewURL, "no \(name).caf in the app")
            #expect(FileManager.default.fileExists(atPath: url.path))
            #expect(url.deletingLastPathComponent().standardizedFileURL == resources.standardizedFileURL)
        }
        #expect(NotificationSoundChoice.bundled("Missing").previewURL == nil)
    }

    @Test func thePopupListsTheAppsSoundsThenTheSystemsSorted() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "agent-mail-sounds-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        for file in ["Tink.aiff", "basso.aiff", "Glass.aiff", "notes.txt"] {
            try Data().write(to: dir.appending(path: file))
        }
        #expect(NotificationSoundChoice.sections(systemSounds: dir) == [
            [.systemDefault, .none, .bundled("Envelope"), .bundled("Prompt"), .bundled("Cursor")],
            [.system("basso"), .system("Glass"), .system("Tink")],
        ])
        let empty = dir.appending(path: "none-here")
        #expect(NotificationSoundChoice.sections(systemSounds: empty).count == 1)
    }

    @Test func systemSoundsPreviewFromTheirFiles() {
        let names = NotificationSoundChoice.systemSoundNames()
        #expect(!names.isEmpty, "no sounds in /System/Library/Sounds")
        for name in names {
            #expect(NotificationSoundChoice.system(name).previewURL?.lastPathComponent == "\(name).aiff")
        }
        #expect(NotificationSoundChoice.system("No Such Sound").previewURL == nil)
    }
}
