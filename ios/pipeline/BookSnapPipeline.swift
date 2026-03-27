import UIKit
import Vision

/// Base pipeline: loads the image and runs Apple Vision with default settings.
///
/// The research loop's test harness uses an enhanced version with document
/// detection, perspective correction, etc. This base version is what ships
/// in the Expo module.
public class BookSnapPipeline {

  enum PipelineError: Error {
    case invalidPath
    case imageLoadFailed
    case recognitionFailed
  }

  public init() {}

  public func processImage(
    from path: String,
    options: [String: Any] = [:]
  ) async throws -> PageResult {
    let uiImage = try Self.loadImage(from: path)
    guard let cgImage = uiImage.cgImage else {
      throw PipelineError.imageLoadFailed
    }

    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = true

    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    try handler.perform([request])

    guard let observations = request.results else {
      throw PipelineError.recognitionFailed
    }

    // Filter out facing-page text by keeping only the dominant text column
    let filtered = Self.filterFacingPage(observations)

    // Filter out running headers/footers from body text
    let bodyObs = Self.filterRunningHeaders(filtered)

    let lines = bodyObs
      .compactMap { $0.topCandidates(1).first?.string }

    let text = Self.joinLines(lines)

    let imageWidth = CGFloat(cgImage.width)
    let imageHeight = CGFloat(cgImage.height)
    let textBounds = Self.computeUnionBounds(bodyObs, imageWidth: imageWidth, imageHeight: imageHeight)

    // Extract page number from all observations (page numbers may be outside main text column)
    let (pageNumber, pageNumberBounds) = Self.extractPageNumber(
      from: observations, imageWidth: imageWidth, imageHeight: imageHeight
    )

    return PageResult(
      text: text,
      textBounds: textBounds,
      pageNumber: pageNumber,
      pageNumberBounds: pageNumberBounds
    )
  }

  /// Join OCR lines into flowing paragraphs, resolving end-of-line hyphenation.
  /// Preserves paragraph breaks (empty lines or lines ending with sentence-ending punctuation
  /// followed by a line starting with a capital letter or quote).
  private static func joinLines(_ lines: [String]) -> String {
    guard !lines.isEmpty else { return "" }

    var result = ""
    for (i, line) in lines.enumerated() {
      if i == 0 {
        result = line
        continue
      }

      let prevLine = lines[i - 1]

      // Detect paragraph break: previous line ends a sentence and current starts with uppercase/quote
      let trimmedPrev = prevLine.trimmingCharacters(in: .whitespaces)
      let trimmedCurr = line.trimmingCharacters(in: .whitespaces)

      if trimmedPrev.isEmpty || trimmedCurr.isEmpty {
        result += "\n" + line
        continue
      }

      let lastChar = trimmedPrev.last ?? " "
      let firstChar = trimmedCurr.first ?? " "

      // Keep paragraph breaks where sentence ends and new paragraph begins
      let sentenceEnders: Set<Character> = [".", "!", "?", "\"", "\u{201D}", "\u{00BB}"]
      let paragraphStart = firstChar.isUppercase || firstChar == "\"" || firstChar == "\u{201C}" || firstChar == "\u{00AB}"

      if sentenceEnders.contains(lastChar) && paragraphStart {
        result += "\n" + line
      } else if trimmedPrev.hasSuffix("-") && !trimmedPrev.hasSuffix("--") && !trimmedPrev.hasSuffix(" -") {
        // Resolve end-of-line hyphenation: remove the hyphen and join with next line
        result = String(result.dropLast()) + line
      } else {
        result += " " + line
      }
    }
    return result
  }

  /// Filter observations to keep only the dominant page (right or left).
  /// In two-page book spreads, text from the facing page bleeds in.
  /// We find the dominant text column by looking at the left edge (x origin)
  /// of the widest observations, then filter out text from the other page.
  private static func filterFacingPage(
    _ observations: [VNRecognizedTextObservation]
  ) -> [VNRecognizedTextObservation] {
    guard observations.count > 3 else { return observations }

    // Sort observations by width (descending) - the widest lines are the main body text
    let sorted = observations.sorted { $0.boundingBox.size.width > $1.boundingBox.size.width }

    // Take the top ~60% widest observations as "body" lines
    let bodyCount = max(3, sorted.count * 6 / 10)
    let bodyObs = Array(sorted.prefix(bodyCount))

    // Find the median left-edge x of body observations
    let leftEdges = bodyObs.map { $0.boundingBox.origin.x }.sorted()
    let medianLeftEdge = leftEdges[leftEdges.count / 2]

    // Find the median right-edge x of body observations
    let rightEdges = bodyObs.map { $0.boundingBox.origin.x + $0.boundingBox.size.width }.sorted()
    let medianRightEdge = rightEdges[rightEdges.count / 2]

    // Filter: keep observations that overlap significantly with the body column
    // An observation is "on the same page" if its horizontal range overlaps
    // with the body column by at least 30%
    let columnWidth = medianRightEdge - medianLeftEdge
    guard columnWidth > 0.1 else { return observations }

    let filtered = observations.filter { obs in
      let obsLeft = obs.boundingBox.origin.x
      let obsRight = obsLeft + obs.boundingBox.size.width
      let overlapLeft = max(medianLeftEdge, obsLeft)
      let overlapRight = min(medianRightEdge, obsRight)
      let overlap = max(0, overlapRight - overlapLeft)
      let obsWidth = obs.boundingBox.size.width
      guard obsWidth > 0 else { return false }
      return overlap / obsWidth > 0.3
    }

    return filtered.isEmpty ? observations : filtered
  }

  /// Filter out running headers/footers — short lines in the top/bottom margins.
  /// Only removes observations that are in the margin AND narrower than most body text.
  private static func filterRunningHeaders(
    _ observations: [VNRecognizedTextObservation]
  ) -> [VNRecognizedTextObservation] {
    guard observations.count > 3 else { return observations }

    let marginThreshold: CGFloat = 0.10  // top/bottom 10%

    // Compute median width of all observations
    let widths = observations.map { $0.boundingBox.size.width }.sorted()
    let medianWidth = widths[widths.count / 2]

    // Remove observations that are in the margin AND narrower than 70% of median body width
    let widthThreshold = medianWidth * 0.70

    let filtered = observations.filter { obs in
      let bbox = obs.boundingBox
      let midY = bbox.origin.y + bbox.size.height / 2.0
      let inMargin = midY < marginThreshold || midY > (1.0 - marginThreshold)

      if inMargin && bbox.size.width < widthThreshold {
        return false  // Remove: it's a short line in the margin (running header/footer)
      }
      return true
    }

    return filtered.isEmpty ? observations : filtered
  }

  private static func computeUnionBounds(
    _ observations: [VNRecognizedTextObservation],
    imageWidth: CGFloat,
    imageHeight: CGFloat
  ) -> BoundingBox {
    guard !observations.isEmpty else { return .zero }

    var minX = CGFloat.greatestFiniteMagnitude
    var minY = CGFloat.greatestFiniteMagnitude
    var maxX: CGFloat = 0
    var maxY: CGFloat = 0

    for obs in observations {
      let bbox = obs.boundingBox
      let x = bbox.origin.x * imageWidth
      let y = (1.0 - bbox.origin.y - bbox.size.height) * imageHeight
      let w = bbox.size.width * imageWidth
      let h = bbox.size.height * imageHeight
      minX = min(minX, x)
      minY = min(minY, y)
      maxX = max(maxX, x + w)
      maxY = max(maxY, y + h)
    }

    return BoundingBox(
      x: Int(minX),
      y: Int(minY),
      width: Int(maxX - minX),
      height: Int(maxY - minY)
    )
  }

  /// Look for a page number in the top or bottom margin of the image.
  /// Checks for standalone numbers and numbers embedded in running headers/footers.
  private static func extractPageNumber(
    from observations: [VNRecognizedTextObservation],
    imageWidth: CGFloat,
    imageHeight: CGFloat
  ) -> (Int?, BoundingBox?) {
    let marginThreshold: CGFloat = 0.15  // top/bottom 15% of image

    var candidates: [(num: Int, obs: VNRecognizedTextObservation)] = []

    for obs in observations {
      let bbox = obs.boundingBox
      let isInBottomMargin = bbox.origin.y < marginThreshold
      let isInTopMargin = (bbox.origin.y + bbox.size.height) > (1.0 - marginThreshold)

      guard isInBottomMargin || isInTopMargin else { continue }
      guard let text = obs.topCandidates(1).first?.string else { continue }
      let trimmed = text.trimmingCharacters(in: .whitespaces)

      // Try standalone number first
      if let num = Int(trimmed), num > 0 && num < 10000 {
        candidates.append((num, obs))
        continue
      }

      // Try number at start of line (e.g. "110 - ELENA FERRANTE", "216 VINELAND")
      let words = trimmed.split(separator: " ", maxSplits: 1)
      if let first = words.first, let num = Int(first), num > 0 && num < 10000 {
        candidates.append((num, obs))
        continue
      }

      // Try number at end of line (e.g. "CHAPTER TITLE 217")
      if let last = trimmed.split(separator: " ").last, let num = Int(last), num > 0 && num < 10000 {
        candidates.append((num, obs))
        continue
      }
    }

    // Prefer standalone numbers, then pick the first candidate
    // Sort: standalone (shorter text) first
    candidates.sort { a, b in
      let aLen = a.obs.topCandidates(1).first?.string.count ?? 0
      let bLen = b.obs.topCandidates(1).first?.string.count ?? 0
      return aLen < bLen
    }

    if let best = candidates.first {
      let bbox = best.obs.boundingBox
      let x = bbox.origin.x * imageWidth
      let y = (1.0 - bbox.origin.y - bbox.size.height) * imageHeight
      let w = bbox.size.width * imageWidth
      let h = bbox.size.height * imageHeight
      let bounds = BoundingBox(x: Int(x), y: Int(y), width: Int(w), height: Int(h))
      return (best.num, bounds)
    }

    return (nil, nil)
  }

  private static func loadImage(from path: String) throws -> UIImage {
    var filePath = path
    if filePath.hasPrefix("file://") {
      filePath = URL(string: filePath)?.path ?? ""
    }
    guard let image = UIImage(contentsOfFile: filePath) else {
      throw PipelineError.invalidPath
    }
    return image
  }
}
