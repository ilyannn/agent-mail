// The thread view's text size, as a page zoom factor: View › Make Text
// Bigger and Smaller step through Safari-like levels, and a pinch sets any
// factor in between.
import Foundation

public enum TextZoom {
    public static let levels: [Double] = [0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3]
    public static let actualSize = 1.0

    public static func clamp(_ zoom: Double) -> Double {
        guard zoom.isFinite else { return actualSize }
        return min(max(zoom, levels.first!), levels.last!)
    }

    /// The next level up from `zoom`, which may lie between levels.
    public static func bigger(than zoom: Double) -> Double {
        levels.first { $0 > zoom + 0.005 } ?? levels.last!
    }

    public static func smaller(than zoom: Double) -> Double {
        levels.last { $0 < zoom - 0.005 } ?? levels.first!
    }
}
