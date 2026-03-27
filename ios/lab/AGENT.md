# BookSnap iOS

On-device book page OCR using Apple Vision. **Lower score is better** (0.0 = perfect).

## Score

The composite score is: `0.98 × CER + 0.02 × (1 - page_number_accuracy)`.

- **CER** (Character Error Rate) dominates — 0.0 = perfect match, 1.0 = completely wrong.
- **Page number accuracy** is a minor signal — fraction of correctly detected page numbers.

Your goal is to minimize this score.

## Constraints

- Preprocessing must complete in under 2s per image on-device.
- The pipeline runs with no network access during evaluation.
- Only Swift and Apple frameworks are available (no third-party pods).

## Pipeline Contract

Swift class `BookSnapPipeline` in `/workspace/pipeline/BookSnapPipeline.swift`:

```swift
public struct BoundingBox: Codable {
    public let x: Int, y: Int, width: Int, height: Int
}

public struct PageResult: Codable {
    public let text: String
    public let textBounds: BoundingBox
    public let pageNumber: Int?
    public let pageNumberBounds: BoundingBox?
}

public class BookSnapPipeline {
    public func processImage(from path: String, options: [String: Any]) async throws -> PageResult
}
```

The shared types (`PageResult`, `BoundingBox`) are in `/workspace/pipeline/PageResult.swift` — do not modify these.

## Available Frameworks

- **Vision** — `VNRecognizeTextRequest` (accurate mode, language correction), `VNDetectDocumentSegmentationRequest` (built-in ML document detector)
- **Core Image** — `CIPerspectiveCorrection`, `CIColorControls`, filters for preprocessing
- **Accelerate / vImage** — fast image processing (histogram equalization, convolution, geometric transforms)
- **UIKit / CoreGraphics** — image loading, EXIF handling, bitmap manipulation
- **NaturalLanguage** — `NLLanguageRecognizer` for language detection

## Tips

- You can view the actual images by reading them from the data directory. This helps understand failure modes like curvature, skew, and facing-page bleed.
- The `arl diagnose` output shows expected vs actual text for each sample — compare them carefully to identify patterns.
- Vision's `VNDetectDocumentSegmentationRequest` + Core Image's `CIPerspectiveCorrection` gives page detection + perspective correction with zero dependencies — this is a strong starting point.
- Vision returns text observations in normalized coordinates (0-1, bottom-left origin). Convert to pixel coordinates: `x * imageWidth`, `(1 - y - height) * imageHeight`.

## Research Directions

1. **Page detection + perspective correction** — `VNDetectDocumentSegmentationRequest` + `CIPerspectiveCorrection`
2. **Text block assembly** — sort Vision observations into reading order, merge into paragraphs
3. **Page number extraction** — detect standalone numbers at top/bottom margins
4. **Facing-page filtering** — remove text fragments from adjacent pages
5. **Contrast enhancement** — Core Image filters (avoid destroying text legibility)
6. **Spell checking / OCR postprocessing** — fix common substitution errors using NaturalLanguage framework
