// Entry point: an AppKit app without a main nib; the delegate builds the
// menus, the status item and the windows in code.
import AppKit

let delegate = AppDelegate()
NSApplication.shared.delegate = delegate
_ = NSApplicationMain(CommandLine.argc, CommandLine.unsafeArgv)
