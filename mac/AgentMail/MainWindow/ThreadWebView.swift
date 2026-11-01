// The thread's web view, and the scheme handler that feeds it attachment
// images. The web view has no file access to the store: images come
// through agent-mail-file, served from the shown thread's files/ directory
// only (AttachmentScheme in AgentMailKit decides what a URL may name).
import AgentMailKit
import AppKit
import WebKit

/// Serves `agent-mail-file:///<thread>/<name>`: an image in the files/
/// directory of the thread on screen, or a failed load.
final class AttachmentSchemeHandler: NSObject, WKURLSchemeHandler {
    /// The thread on screen and its files/ directory.
    var thread: (id: String, files: URL)?
    /// Bigger images aren't loaded inline; their link still opens them.
    static let maximumBytes = 64 << 20
    #if DEBUG
    private(set) var log: [String] = []
    #endif

    func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        guard let url = task.request.url, let thread,
              let file = AttachmentScheme.fileURL(for: url, thread: thread.id, filesDirectory: thread.files),
              AttachmentScheme.isImage(file.lastPathComponent),
              let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size <= Self.maximumBytes,
              let data = try? Data(contentsOf: file, options: .mappedIfSafe) else {
            #if DEBUG
            log.append("refused \(task.request.url?.absoluteString ?? "-")")
            #endif
            task.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        #if DEBUG
        log.append("served \(file.lastPathComponent) \(data.count)")
        #endif
        let response = URLResponse(url: url, mimeType: AttachmentScheme.mimeType(forName: file.lastPathComponent),
                                   expectedContentLength: data.count, textEncodingName: nil)
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    // Loads finish synchronously in start; there is nothing to stop.
    func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {}
}

/// The thread view's WKWebView: a pinch changes the text size (a page zoom,
/// which reflows, rather than WebKit's magnification, which doesn't), and
/// the selection is noted on every mouse down, because clicking a card's
/// Reply button can clear it before the click arrives.
final class ThreadWebView: WKWebView {
    private(set) var selectionAtMouseDown = ""
    /// The pinch's scale step, and whether the gesture ended.
    var onPinch: (_ magnification: Double, _ ended: Bool) -> Void = { _, _ in }

    override func mouseDown(with event: NSEvent) {
        // Sent before the click, so the page answers with the selection
        // as it was.
        evaluateJavaScript("window.getSelection().toString()") { [weak self] result, _ in
            self?.selectionAtMouseDown = result as? String ?? ""
        }
        super.mouseDown(with: event)
    }

    override func magnify(with event: NSEvent) {
        onPinch(event.magnification, event.phase.contains(.ended) || event.phase.contains(.cancelled))
    }
}
