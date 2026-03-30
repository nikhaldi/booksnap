import UIKit
import Vision
import CoreImage
import NaturalLanguage

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

    // Enhance contrast to improve OCR on uneven lighting / curved pages
    let enhancedImage = Self.enhanceContrast(cgImage) ?? cgImage

    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = true

    let handler = VNImageRequestHandler(cgImage: enhancedImage, options: [:])
    try handler.perform([request])

    guard let observations = request.results else {
      throw PipelineError.recognitionFailed
    }

    // Filter out facing-page text by keeping only the dominant text column
    let filtered = Self.filterFacingPage(observations)

    // Filter out running headers/footers from body text
    let bodyObsUnsorted = Self.filterRunningHeaders(filtered)

    // Sort observations into reading order: group by Y-band, sort bands top-to-bottom
    let bodyObs = Self.sortReadingOrder(bodyObsUnsorted)

    let lines = bodyObs
      .compactMap { $0.topCandidates(1).first?.string }

    var text = Self.joinLines(lines)

    // Strip running header prefix if the text starts with a header-like pattern
    text = Self.stripRunningHeader(text)

    let imageWidth = CGFloat(cgImage.width)
    let imageHeight = CGFloat(cgImage.height)
    let textBounds = Self.computeUnionBounds(bodyObs, imageWidth: imageWidth, imageHeight: imageHeight)

    // Extract page number from all observations (page numbers may be outside main text column)
    var (pageNumber, pageNumberBounds) = Self.extractPageNumber(
      from: observations, imageWidth: imageWidth, imageHeight: imageHeight
    )

    // Fallback: if no page number found in margins (or found a suspiciously small one),
    // check first/last body observations for embedded page numbers from merged headers.
    // Running headers often appear as "Title 109" or "109 Title" at start/end of body text.
    if pageNumber == nil || (pageNumber ?? 0) < 10 {
      let fallbackObs = (Array(bodyObs.prefix(3)) + Array(bodyObs.suffix(2)))
        .compactMap { $0 as VNRecognizedTextObservation? }
      for obs in fallbackObs {
        guard let text = obs.topCandidates(1).first?.string else { continue }
        let words = text.trimmingCharacters(in: .whitespaces).split(separator: " ").map(String.init)
        // Scan first 5 words and last 3 words for a page number
        let checkWords = Array(words.prefix(5)) + Array(words.suffix(3))
        for word in checkWords {
          if let num = Self.parsePageNum(word), num >= 10 {
            pageNumber = num
            let bbox = obs.boundingBox
            pageNumberBounds = BoundingBox(
              x: Int(bbox.origin.x * imageWidth),
              y: Int((1.0 - bbox.origin.y - bbox.size.height) * imageHeight),
              width: Int(bbox.size.width * imageWidth),
              height: Int(bbox.size.height * imageHeight)
            )
            break
          }
        }
        if pageNumber != nil && (pageNumber ?? 0) >= 10 { break }
      }
    }

    // Strip trailing page number from body text if Vision merged it with the last line
    if let pageNum = pageNumber {
      let pnStr = String(pageNum)
      let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
      if trimmed.hasSuffix(pnStr) {
        let beforeIdx = trimmed.index(trimmed.endIndex, offsetBy: -pnStr.count)
        // Only strip if the number is preceded by whitespace or is at start
        if beforeIdx == trimmed.startIndex {
          text = String(trimmed.dropLast(pnStr.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
          let charBefore = trimmed[trimmed.index(before: beforeIdx)]
          if charBefore == " " || charBefore == "\n" || !charBefore.isLetter {
            text = String(trimmed.dropLast(pnStr.count)).trimmingCharacters(in: .whitespacesAndNewlines)
          }
        }
      }
    }

    // Fix OCR character confusion errors using UITextChecker as dictionary validator
    let spellCheck = options["spellCheck"] as? Bool ?? true
    if spellCheck {
      text = Self.fixOCRConfusions(text)
    }

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
    for (index, line) in lines.enumerated() {
      if index == 0 {
        result = line
        continue
      }

      let prevLine = lines[index - 1]

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
      // Include both « and » as enders/starters for French/Italian and German dialogue
      let sentenceEnders: Set<Character> = [".", "!", "?", ":", "\"", "\u{201D}", "\u{00BB}", "\u{00AB}"]
      let paragraphStarters: Set<Character> = ["\"", "\u{201C}", "\u{00AB}", "\u{00BB}"]
      let paragraphStart = firstChar.isUppercase || paragraphStarters.contains(firstChar)

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

  /// Strip a running header prefix from the body text.
  /// Detects patterns like "Title 109 body text..." or "109• Title body text..."
  /// where a short header with a page number precedes the actual body text.
  private static func stripRunningHeader(_ text: String) -> String {
    let words = text.split(separator: " ", maxSplits: 14).map(String.init)
    guard words.count > 5 else { return text }

    // Look for a page number in the first 8 words (expanded to handle longer headers
    // like "Debacle: Lorraine, Ardennes, Charleroi, Mons » 267")
    for idx in 0..<min(8, words.count) {
      if parsePageNum(words[idx]) != nil {
        // Found a number at position i. Check if text after it looks like body text.
        // A running header typically has: [title words] [number] [body text starts]
        // or: [number] [title words] [body text starts]
        // Body text usually starts with a lowercase word or continues a sentence.
        let afterIdx = idx + 1
        guard afterIdx < words.count else { continue }

        // Check if there's a clear boundary: the word after the number region
        // starts with lowercase, suggesting it's body text starting mid-sentence
        let bodyStartIdx: Int
        if idx == 0 {
          // Number is first: "109 Title Text body..." or "109• Title body..."
          // Find where title-case words end
          var scan = 1
          while scan < min(8, words.count) {
            let word = words[scan]
            let letter = word.first(where: { $0.isLetter })
            if let letter, letter.isLowercase {
              break
            }
            scan += 1
          }
          bodyStartIdx = scan
        } else {
          // Number is in the middle: "Title 109 body..."
          bodyStartIdx = afterIdx
        }

        guard bodyStartIdx < words.count && bodyStartIdx <= 9 else { continue }

        // Verify: the word at bodyStartIdx should start lowercase (mid-sentence)
        let bodyWord = words[bodyStartIdx]
        let letter = bodyWord.first(where: { $0.isLetter })
        if let letter, letter.isLowercase {
          // Strip the header prefix
          let headerWords = words[0..<bodyStartIdx]
          let headerPrefix = headerWords.joined(separator: " ") + " "
          if text.hasPrefix(headerPrefix) {
            return String(text.dropFirst(headerPrefix.count))
          }
        }
      }
    }

    return text
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
      return overlap / obsWidth > 0.35
    }

    return filtered.isEmpty ? observations : filtered
  }

  /// Filter out running headers/footers — short lines in the top/bottom margins.
  /// Only removes observations that are in the margin AND narrower than most body text.
  private static func filterRunningHeaders(
    _ observations: [VNRecognizedTextObservation]
  ) -> [VNRecognizedTextObservation] {
    guard observations.count > 3 else { return observations }

    let marginThreshold: CGFloat = 0.12  // top/bottom 12%

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

  /// Sort observations into reading order by grouping into Y-bands (lines)
  /// and sorting bands top-to-bottom. Within each band, preserves original order.
  /// Uses the median line height as the Y-tolerance for grouping.
  private static func sortReadingOrder(
    _ observations: [VNRecognizedTextObservation]
  ) -> [VNRecognizedTextObservation] {
    guard observations.count > 2 else { return observations }

    // Compute median observation height as the Y-tolerance
    let heights = observations.map { $0.boundingBox.size.height }.sorted()
    let medianHeight = heights[heights.count / 2]
    let yTolerance = medianHeight * 0.75  // three-quarters of a line height

    // Sort by Y center (descending = top-to-bottom in Vision coords)
    // Then group consecutive observations that are within yTolerance
    let sorted = observations.enumerated().sorted {
      let y0 = $0.element.boundingBox.origin.y + $0.element.boundingBox.size.height / 2.0
      let y1 = $1.element.boundingBox.origin.y + $1.element.boundingBox.size.height / 2.0
      return y0 > y1  // descending Y = top to bottom
    }

    // Group into Y-bands
    var bands: [[Int]] = []  // each band is a list of original indices
    var currentBand: [Int] = []
    var bandY: CGFloat = 0

    for (origIdx, obs) in sorted {
      let midY = obs.boundingBox.origin.y + obs.boundingBox.size.height / 2.0
      if currentBand.isEmpty {
        currentBand = [origIdx]
        bandY = midY
      } else if abs(midY - bandY) <= yTolerance {
        currentBand.append(origIdx)
      } else {
        bands.append(currentBand)
        currentBand = [origIdx]
        bandY = midY
      }
    }
    if !currentBand.isEmpty {
      bands.append(currentBand)
    }

    // Within each band, sort by original index to preserve Vision's within-line order
    var result: [VNRecognizedTextObservation] = []
    for band in bands {
      let sortedBand = band.sorted()  // original order within the band
      for idx in sortedBand {
        result.append(observations[idx])
      }
    }

    return result
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
      let width = bbox.size.width * imageWidth
      let height = bbox.size.height * imageHeight
      minX = min(minX, x)
      minY = min(minY, y)
      maxX = max(maxX, x + width)
      maxY = max(maxY, y + height)
    }

    return BoundingBox(
      x: Int(minX),
      y: Int(minY),
      width: Int(maxX - minX),
      height: Int(maxY - minY)
    )
  }

  /// Extract digits from a token, stripping punctuation like bullets and dashes.
  private static func parsePageNum(_ token: String) -> Int? {
    let digits = token.filter { $0.isNumber }
    guard !digits.isEmpty else { return nil }
    // The token should be mostly digits (allow 1-2 punctuation chars like "86•" or "265.")
    guard digits.count >= token.count - 2 else { return nil }
    guard let num = Int(digits), num > 0 && num < 10000 else { return nil }
    return num
  }

  /// Look for a page number in the top or bottom margin of the image.
  /// Checks for standalone numbers and numbers embedded in running headers/footers.
  private static func extractPageNumber(
    from observations: [VNRecognizedTextObservation],
    imageWidth: CGFloat,
    imageHeight: CGFloat
  ) -> (Int?, BoundingBox?) {
    let marginThreshold: CGFloat = 0.25  // top/bottom 25% of image

    // (num, observation, isStandalone)
    var candidates: [(Int, VNRecognizedTextObservation, Bool)] = []

    for obs in observations {
      let bbox = obs.boundingBox
      let isInBottomMargin = bbox.origin.y < marginThreshold
      let isInTopMargin = (bbox.origin.y + bbox.size.height) > (1.0 - marginThreshold)

      guard isInBottomMargin || isInTopMargin else { continue }
      guard let text = obs.topCandidates(1).first?.string else { continue }
      let trimmed = text.trimmingCharacters(in: .whitespaces)

      // Try standalone number (with optional punctuation like "86•")
      if let num = parsePageNum(trimmed) {
        candidates.append((num, obs, true))
        continue
      }

      let words = trimmed.split(separator: " ").map(String.init)

      // Try number at start of line (e.g. "110 - ELENA FERRANTE")
      if let first = words.first, let num = parsePageNum(first) {
        candidates.append((num, obs, false))
        continue
      }

      // Try number at end of line (e.g. "CHAPTER TITLE 217", "Du côté de chez Swann 307")
      if let last = words.last, let num = parsePageNum(last) {
        candidates.append((num, obs, false))
        continue
      }

      // Try any number token in the line (e.g. "TITLE • 265")
      for idx in 1..<words.count {
        if let num = parsePageNum(words[idx]) {
          candidates.append((num, obs, false))
          break
        }
      }
    }

    guard !candidates.isEmpty else { return (nil, nil) }

    // Select best candidate:
    // 1. Standalone numbers >= 10 are most reliable (real page numbers, not footnote markers)
    // 2. Numbers from header lines are reliable
    // 3. Small standalone numbers (< 10) are least reliable (could be footnote markers)
    let chosen: (Int, VNRecognizedTextObservation, Bool)
    let standaloneGood = candidates.filter { $0.2 && $0.0 >= 10 }
    let headerNums = candidates.filter { !$0.2 }

    if let standalone = standaloneGood.first {
      chosen = standalone
    } else if let header = headerNums.first {
      chosen = header
    } else {
      chosen = candidates.first!
    }

    let bbox = chosen.1.boundingBox
    let x = bbox.origin.x * imageWidth
    let y = (1.0 - bbox.origin.y - bbox.size.height) * imageHeight
    let width = bbox.size.width * imageWidth
    let height = bbox.size.height * imageHeight
    let bounds = BoundingBox(x: Int(x), y: Int(y), width: Int(width), height: Int(height))
    return (chosen.0, bounds)
  }

  /// Apply mild contrast enhancement using Core Image.
  private static func enhanceContrast(_ cgImage: CGImage) -> CGImage? {
    let ciImage = CIImage(cgImage: cgImage)
    guard let filter = CIFilter(name: "CIColorControls") else { return nil }
    filter.setValue(ciImage, forKey: kCIInputImageKey)
    filter.setValue(1.1, forKey: kCIInputContrastKey)     // slight contrast boost
    filter.setValue(0.02, forKey: kCIInputBrightnessKey)   // tiny brightness increase
    guard let output = filter.outputImage else { return nil }
    let context = CIContext()
    return context.createCGImage(output, from: output.extent)
  }

  /// Fix common OCR character confusions by trying known swap patterns and
  /// validating corrections against UITextChecker's dictionary.
  /// Only corrects words where exactly one swap produces a valid dictionary word.
  private static func fixOCRConfusions(_ text: String) -> String {
    let recognizer = NLLanguageRecognizer()
    recognizer.processString(text)
    guard let detectedLang = recognizer.dominantLanguage else { return text }

    let langCode: String
    switch detectedLang {
    case .italian: langCode = "it"
    case .german: langCode = "de"
    case .french: langCode = "fr"
    case .english: langCode = "en"
    case .spanish: langCode = "es"
    case .portuguese: langCode = "pt"
    default: return text
    }

    let available = UITextChecker.availableLanguages
    guard let checkerLang = available.first(where: { $0.hasPrefix(langCode) }) else { return text }

    let checker = UITextChecker()
    let nsText = text as NSString
    let fullRange = NSRange(location: 0, length: nsText.length)

    // OCR-confusable character pairs (single character swaps)
    let confusablePairs: [(Character, Character)] = [
      ("f", "t"), ("t", "f"),
      ("l", "i"), ("i", "l"),
    ]

    var corrections: [(NSRange, String)] = []
    var searchStart = 0

    while searchStart < nsText.length {
      let misspelledRange = checker.rangeOfMisspelledWord(
        in: text,
        range: fullRange,
        startingAt: searchStart,
        wrap: false,
        language: checkerLang
      )

      guard misspelledRange.location != NSNotFound else { break }

      let misspelled = nsText.substring(with: misspelledRange)
      searchStart = misspelledRange.location + misspelledRange.length

      // Skip short words and words with non-letter characters
      guard misspelled.count >= 4 else { continue }
      guard misspelled.allSatisfy({ $0.isLetter }) else { continue }

      // Try each confusable swap at each character position
      var validCandidates: [String] = []
      let chars = Array(misspelled)

      for i in 0..<chars.count {
        for (from, to) in confusablePairs {
          let ch = chars[i]
          // Match case-insensitively
          let chLower = Character(ch.lowercased())
          if chLower == from {
            var newChars = chars
            // Preserve case
            if ch.isUppercase {
              newChars[i] = Character(String(to).uppercased())
            } else {
              newChars[i] = to
            }
            let candidate = String(newChars)
            if candidate == misspelled { continue }

            // Check if this candidate is a valid word
            let candidateNS = candidate as NSString
            let candidateRange = NSRange(location: 0, length: candidateNS.length)
            let check = checker.rangeOfMisspelledWord(
              in: candidate,
              range: candidateRange,
              startingAt: 0,
              wrap: false,
              language: checkerLang
            )
            if check.location == NSNotFound {
              // It's a valid word!
              validCandidates.append(candidate)
            }
          }
        }
      }

      // Only apply if exactly one valid candidate was found (unambiguous)
      if validCandidates.count == 1 {
        corrections.append((misspelledRange, validCandidates[0]))
      }
    }

    guard !corrections.isEmpty else { return text }

    var result = text as NSString
    for (range, correction) in corrections.reversed() {
      result = result.replacingCharacters(in: range, with: correction) as NSString
    }

    return result as String
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
