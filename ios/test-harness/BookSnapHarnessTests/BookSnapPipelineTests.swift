import XCTest
@testable import BookSnapHarness

final class BookSnapPipelineTests: XCTestCase {

    private let pipeline = BookSnapPipeline()

    func testProcessImageWithInvalidPath() async {
        do {
            _ = try await pipeline.processImage(from: "/nonexistent/path.jpg")
            XCTFail("Expected an error for invalid path")
        } catch {
            // Expected — invalid path should throw
        }
    }

    func testProcessImageWithFileURIPrefix() async {
        do {
            _ = try await pipeline.processImage(from: "file:///nonexistent/path.jpg")
            XCTFail("Expected an error for invalid path")
        } catch {
            // Expected — file:// prefix should be stripped, then fail on missing file
        }
    }

    func testProcessImageWithRenderedText() async throws {
        // Create an image with rendered text to exercise the full Vision pipeline.
        let imagePath = try Self.createTextImage(
            text: "Hello World",
            size: CGSize(width: 400, height: 200)
        )
        defer { try? FileManager.default.removeItem(atPath: imagePath) }

        let result = try await pipeline.processImage(from: imagePath)

        XCTAssertFalse(result.text.isEmpty, "Pipeline should extract text from the image")
        XCTAssertTrue(
            result.text.lowercased().contains("hello"),
            "Extracted text should contain 'hello', got: \(result.text)"
        )
        XCTAssertNil(result.pageNumber, "No page number should be detected in body-only image")
        XCTAssertNil(result.pageNumberBounds, "No page number bounds should be returned")
    }

    func testProcessImageReturnsBounds() async throws {
        let imagePath = try Self.createTextImage(
            text: "Bounding box test",
            size: CGSize(width: 600, height: 300)
        )
        defer { try? FileManager.default.removeItem(atPath: imagePath) }

        let result = try await pipeline.processImage(from: imagePath)

        XCTAssertFalse(result.text.isEmpty)

        // Text should be roughly centered in the 600x300 image, not at the edges
        let bounds = result.textBounds
        XCTAssertGreaterThan(bounds.width, 100, "Text bounds width should be substantial")
        XCTAssertGreaterThan(bounds.height, 20, "Text bounds height should be substantial")
        XCTAssertGreaterThanOrEqual(bounds.x, 0, "Text bounds x should be non-negative")
        XCTAssertGreaterThanOrEqual(bounds.y, 0, "Text bounds y should be non-negative")
    }

    func testHyphenationIsResolved() async throws {
        // Render two lines where the first ends with a hyphenated word,
        // simulating a line break mid-word as found in printed books.
        let size = CGSize(width: 600, height: 300)
        let imagePath = try Self.createMultiLineImage(
            lines: ["This is a book about pre-", "processing of images."],
            size: size
        )
        defer { try? FileManager.default.removeItem(atPath: imagePath) }

        let result = try await pipeline.processImage(from: imagePath)

        XCTAssertFalse(result.text.isEmpty)
        XCTAssertTrue(
            result.text.contains("preprocessing"),
            "Hyphenated word should be joined: got '\(result.text)'"
        )
        XCTAssertFalse(
            result.text.contains("pre-"),
            "Trailing hyphen should be removed: got '\(result.text)'"
        )
    }

    func testProcessImageWithPageNumber() async throws {
        // Create a tall image with body text in the center and a page number at the bottom,
        // simulating a real book page layout.
        let size = CGSize(width: 600, height: 800)
        let imagePath = try Self.createBookPageImage(
            bodyText: "This is the body text of a book page with enough words to be recognized.",
            pageNumber: "142",
            size: size
        )
        defer { try? FileManager.default.removeItem(atPath: imagePath) }

        let result = try await pipeline.processImage(from: imagePath)

        XCTAssertFalse(result.text.isEmpty)
        XCTAssertEqual(result.pageNumber, 142, "Should detect page number 142")
        XCTAssertNotNil(result.pageNumberBounds, "Should return page number bounding box")
        if let bounds = result.pageNumberBounds {
            XCTAssertGreaterThan(bounds.width, 0)
            XCTAssertGreaterThan(bounds.height, 0)
            // Page number is at the bottom, so its y should be in the lower portion of the image
            XCTAssertGreaterThan(bounds.y, Int(size.height) / 2, "Page number should be in lower half")
        }
    }

    // MARK: - Helpers

    /// Renders body text centered in an image.
    private static func createTextImage(text: String, size: CGSize) throws -> String {
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))

            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 32),
                .foregroundColor: UIColor.black,
            ]
            let textRect = CGRect(x: 20, y: 40, width: size.width - 40, height: size.height - 80)
            (text as NSString).draw(in: textRect, withAttributes: attributes)
        }

        return try writeTempPNG(image)
    }

    /// Renders a book page with body text and a page number in the bottom margin.
    private static func createBookPageImage(
        bodyText: String,
        pageNumber: String,
        size: CGSize
    ) throws -> String {
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))

            // Body text in the upper/center area
            let bodyAttributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 28),
                .foregroundColor: UIColor.black,
            ]
            let bodyRect = CGRect(x: 40, y: 60, width: size.width - 80, height: size.height * 0.6)
            (bodyText as NSString).draw(in: bodyRect, withAttributes: bodyAttributes)

            // Page number centered at the bottom margin
            let numAttributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 24),
                .foregroundColor: UIColor.black,
            ]
            let numSize = (pageNumber as NSString).size(withAttributes: numAttributes)
            let numRect = CGRect(
                x: (size.width - numSize.width) / 2,
                y: size.height - 60,
                width: numSize.width,
                height: numSize.height
            )
            (pageNumber as NSString).draw(in: numRect, withAttributes: numAttributes)
        }

        return try writeTempPNG(image)
    }

    /// Renders lines of text at fixed vertical positions so Vision recognizes them as separate lines.
    private static func createMultiLineImage(lines: [String], size: CGSize) throws -> String {
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))

            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 32),
                .foregroundColor: UIColor.black,
            ]
            for (index, line) in lines.enumerated() {
                let yOffset = 40.0 + CGFloat(index) * 50.0
                let rect = CGRect(x: 20, y: yOffset, width: size.width - 40, height: 45)
                (line as NSString).draw(in: rect, withAttributes: attributes)
            }
        }

        return try writeTempPNG(image)
    }

    private static func writeTempPNG(_ image: UIImage) throws -> String {
        guard let data = image.pngData() else {
            throw NSError(domain: "TestHelper", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Failed to create PNG data",
            ])
        }
        let path = NSTemporaryDirectory() + "booksnap_test_\(UUID().uuidString).png"
        try data.write(to: URL(fileURLWithPath: path))
        return path
    }
}
