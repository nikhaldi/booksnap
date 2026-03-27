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

    let lines = filtered
      .compactMap { $0.topCandidates(1).first?.string }

    let text = Self.joinLines(lines)

    let imageWidth = CGFloat(cgImage.width)
    let imageHeight = CGFloat(cgImage.height)
    let textBounds = Self.computeUnionBounds(filtered, imageWidth: imageWidth, imageHeight: imageHeight)

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
  /// We identify the dominant column by finding which side of the image
  /// contains the majority of text (by character count), then filter out
  /// observations whose center-x is on the wrong side.
  private static func filterFacingPage(
    _ observations: [VNRecognizedTextObservation]
  ) -> [VNRecognizedTextObservation] {
    guard observations.count > 2 else { return observations }

    // Compute weighted x-center for each observation (weighted by text length)
    // Vision coords: x in [0,1], origin bottom-left
    var leftWeight = 0
    var rightWeight = 0

    for obs in observations {
      let centerX = obs.boundingBox.origin.x + obs.boundingBox.size.width / 2
      let textLen = obs.topCandidates(1).first?.string.count ?? 0
      if centerX < 0.5 {
        leftWeight += textLen
      } else {
        rightWeight += textLen
      }
    }

    // If text is predominantly on one side (>70% of chars), filter out the other side
    let totalWeight = leftWeight + rightWeight
    guard totalWeight > 0 else { return observations }

    let dominantIsRight = rightWeight >= leftWeight
    let dominantRatio = CGFloat(max(leftWeight, rightWeight)) / CGFloat(totalWeight)

    // Only filter if there's a clear dominant side (>65% of text)
    guard dominantRatio > 0.65 else { return observations }

    // Find the median x-center of dominant-side observations to set a more precise cutoff
    let dominantObs = observations.filter { obs in
      let cx = obs.boundingBox.origin.x + obs.boundingBox.size.width / 2
      return dominantIsRight ? cx >= 0.35 : cx <= 0.65
    }

    // Keep observations on the dominant side, with some tolerance
    let filtered = observations.filter { obs in
      let cx = obs.boundingBox.origin.x + obs.boundingBox.size.width / 2
      if dominantIsRight {
        return cx >= 0.25  // Keep anything from ~25% rightward
      } else {
        return cx <= 0.75  // Keep anything from ~75% leftward
      }
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

  /// Look for a standalone number in the top or bottom 15% of the image — likely a page number.
  private static func extractPageNumber(
    from observations: [VNRecognizedTextObservation],
    imageWidth: CGFloat,
    imageHeight: CGFloat
  ) -> (Int?, BoundingBox?) {
    let marginThreshold: CGFloat = 0.15  // top/bottom 15% of image

    for obs in observations {
      let bbox = obs.boundingBox
      // Check if observation is in top or bottom margin
      // Vision coords: origin is bottom-left, y goes up
      let isInBottomMargin = bbox.origin.y < marginThreshold
      let isInTopMargin = (bbox.origin.y + bbox.size.height) > (1.0 - marginThreshold)

      guard isInBottomMargin || isInTopMargin else { continue }

      guard let candidate = obs.topCandidates(1).first?.string else { continue }
      let trimmed = candidate.trimmingCharacters(in: .whitespaces)

      // Must be a standalone number (1-4 digits)
      if let num = Int(trimmed), num > 0 && num < 10000 {
        let x = bbox.origin.x * imageWidth
        let y = (1.0 - bbox.origin.y - bbox.size.height) * imageHeight
        let w = bbox.size.width * imageWidth
        let h = bbox.size.height * imageHeight
        let bounds = BoundingBox(x: Int(x), y: Int(y), width: Int(w), height: Int(h))
        return (num, bounds)
      }
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
