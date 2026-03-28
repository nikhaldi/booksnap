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
    }

    func testProcessImageReturnsBounds() async throws {
        let imagePath = try Self.createTextImage(
            text: "Bounding box test",
            size: CGSize(width: 600, height: 300)
        )
        defer { try? FileManager.default.removeItem(atPath: imagePath) }

        let result = try await pipeline.processImage(from: imagePath)

        // Text bounds should be non-zero if text was recognized
        if !result.text.isEmpty {
            XCTAssertTrue(
                result.textBounds.width > 0 && result.textBounds.height > 0,
                "Text bounds should be non-zero when text is recognized"
            )
        }
    }

    // MARK: - Helpers

    /// Renders text into a PNG image and returns its file path.
    private static func createTextImage(text: String, size: CGSize) throws -> String {
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            // White background
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))

            // Draw black text
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 32),
                .foregroundColor: UIColor.black,
            ]
            let textRect = CGRect(x: 20, y: 40, width: size.width - 40, height: size.height - 80)
            (text as NSString).draw(in: textRect, withAttributes: attributes)
        }

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
