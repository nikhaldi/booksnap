import Foundation

/// Axis-aligned bounding box in pixel coordinates of the original image.
public struct BoundingBox: Codable {
    public let x: Int
    public let y: Int
    public let width: Int
    public let height: Int

    public init(x: Int, y: Int, width: Int, height: Int) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    public static let zero = BoundingBox(x: 0, y: 0, width: 0, height: 0)
}

/// Result of processing a single page image.
public struct PageResult: Codable {
    /// Extracted body text with paragraph breaks.
    public let text: String
    /// Bounding box of the body text region in the original image (pixels).
    public let textBounds: BoundingBox
    /// Page number if detected (e.g. from top/bottom margin).
    public let pageNumber: Int?
    /// Bounding box of the page number in the original image (pixels).
    public let pageNumberBounds: BoundingBox?

    public init(
        text: String,
        textBounds: BoundingBox = .zero,
        pageNumber: Int? = nil,
        pageNumberBounds: BoundingBox? = nil
    ) {
        self.text = text
        self.textBounds = textBounds
        self.pageNumber = pageNumber
        self.pageNumberBounds = pageNumberBounds
    }
}
