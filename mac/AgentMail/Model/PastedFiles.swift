// Attachments that arrive without a file of their own: an image pasted from
// the clipboard (a screenshot copied there), or a file another app promises
// on a drop (Photos). They are written here, one folder each, for the CLI to
// copy into the thread; once sent or thrown away, they go.
import AppKit

enum PastedFiles {
    static let directory = FileManager.default.temporaryDirectory
        .appending(path: "AgentMail Pasted", directoryHint: .isDirectory)

    /// A new, empty folder here.
    static func newFolder() throws -> URL {
        let folder = directory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// `data` saved as `name`, in a folder of its own.
    static func write(_ data: Data, name: String) throws -> URL {
        let file = try newFolder().appending(path: name)
        try data.write(to: file)
        return file
    }

    /// Deletes those of `files` that were written here, with their folders;
    /// any other file is the user's own and stays.
    static func remove(_ files: [URL]) {
        let root = directory.standardizedFileURL.path
        for file in files {
            let folder = file.standardizedFileURL.deletingLastPathComponent()
            guard folder.deletingLastPathComponent().path == root else { continue }
            try? FileManager.default.removeItem(at: folder)
        }
    }
}

/// What a paste or a drop attaches, rather than putting into the text.
enum PastedAttachment {
    /// Files, from Finder or anything else that hands over file URLs.
    case files([URL])
    /// An image with no text alongside: a screenshot on the clipboard.
    case image(Data, fileExtension: String)
    /// Files another app writes once told where (Photos, Mail).
    case promises([NSFilePromiseReceiver])

    /// The types the reply box accepts on top of text.
    static let pasteboardTypes: [NSPasteboard.PasteboardType] =
        [.fileURL, .png, .tiff, jpegType] + NSFilePromiseReceiver.readableDraggedTypes.map { NSPasteboard.PasteboardType($0) }
    private static let jpegType = NSPasteboard.PasteboardType("public.jpeg")

    /// Files first, then promised files, then an image that comes without
    /// text; nil leaves the pasteboard to the text view. An image that
    /// comes with text (a picture of a Word selection) pastes as the text.
    static func read(_ pasteboard: NSPasteboard) -> PastedAttachment? {
        let fileURLs = pasteboard.readObjects(forClasses: [NSURL.self],
                                              options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        if !fileURLs.isEmpty { return .files(fileURLs) }
        let promises = pasteboard.readObjects(forClasses: [NSFilePromiseReceiver.self]) as? [NSFilePromiseReceiver] ?? []
        if !promises.isEmpty { return .promises(promises) }
        guard pasteboard.string(forType: .string) == nil else { return nil }
        if let png = pasteboard.data(forType: .png) { return .image(png, fileExtension: "png") }
        if let jpeg = pasteboard.data(forType: jpegType) { return .image(jpeg, fileExtension: "jpg") }
        if let tiff = pasteboard.data(forType: .tiff),
           let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
            return .image(png, fileExtension: "png")
        }
        return nil
    }
}
