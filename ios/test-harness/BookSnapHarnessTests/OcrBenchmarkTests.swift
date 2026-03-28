import XCTest
@testable import BookSnapHarness

/// XCTest that runs the pipeline on a batch of images and writes results as JSON.
///
/// Input files are copied to the app's Documents/booksnap/ directory by the daemon.
///   Input:  Documents/booksnap/manifest.json
///   Output: Documents/booksnap/results.json
final class OcrBenchmarkTests: XCTestCase {

    struct Manifest: Codable {
        struct Entry: Codable {
            let id: String
            let path: String
        }
        let images: [Entry]
    }

    struct ImageResult: Codable {
        let image_id: String
        let extracted_text: String
        let latency_ms: Int
        let platform: String
        let error: String?
        let page_number: Int?
        let text_bounds: BoundingBox?
        let page_number_bounds: BoundingBox?
    }

    struct Results: Codable {
        let results: [ImageResult]
    }

    func testRunBenchmark() async throws {
        let fileManager = FileManager.default
        let docsDir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        let baseDir = docsDir.appendingPathComponent("booksnap")

        let manifestURL = baseDir.appendingPathComponent("manifest.json")
        XCTAssertTrue(fileManager.fileExists(atPath: manifestURL.path),
                      "Manifest not found. Push it via the daemon.")

        let manifestData = try Data(contentsOf: manifestURL)
        let manifest = try JSONDecoder().decode(Manifest.self, from: manifestData)

        let pipeline = BookSnapPipeline()

        var results: [ImageResult] = []

        for entry in manifest.images {
            let imagePath = baseDir.appendingPathComponent(entry.path).path

            guard fileManager.fileExists(atPath: imagePath) else {
                results.append(ImageResult(
                    image_id: entry.id,
                    extracted_text: "",
                    latency_ms: 0,
                    platform: "ios",
                    error: "Image not found: \(imagePath)",
                    page_number: nil,
                    text_bounds: nil,
                    page_number_bounds: nil
                ))
                continue
            }

            do {
                let startTime = CFAbsoluteTimeGetCurrent()
                let result = try await pipeline.processImage(from: imagePath)
                let elapsedMs = Int((CFAbsoluteTimeGetCurrent() - startTime) * 1000)

                results.append(ImageResult(
                    image_id: entry.id,
                    extracted_text: result.text,
                    latency_ms: elapsedMs,
                    platform: "ios",
                    error: nil,
                    page_number: result.pageNumber,
                    text_bounds: result.textBounds.width > 0 ? result.textBounds : nil,
                    page_number_bounds: result.pageNumberBounds
                ))
            } catch {
                results.append(ImageResult(
                    image_id: entry.id,
                    extracted_text: "",
                    latency_ms: 0,
                    platform: "ios",
                    error: "\(type(of: error)): \(error.localizedDescription)",
                    page_number: nil,
                    text_bounds: nil,
                    page_number_bounds: nil
                ))
            }
        }

        // Write results
        let outputURL = baseDir.appendingPathComponent("results.json")
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        let outputData = try encoder.encode(Results(results: results))
        try outputData.write(to: outputURL)
    }
}
