# BookSnap Android

On-device book page OCR. **Lower score is better** (0.0 = perfect).

## Score

The composite score is: `0.98 × CER + 0.02 × (1 - page_number_accuracy)`.

- **CER** (Character Error Rate) dominates — it measures how different the extracted text is from the ground truth. 0.0 = perfect match, 1.0 = completely wrong.
- **Page number accuracy** is a minor signal — fraction of correctly detected page numbers.

Your goal is to minimize this score.

## Constraints

- Preprocessing must complete in under 1s per image on-device.
- The pipeline runs with no network access during evaluation.

## Pipeline Contract

Kotlin class at `dev.booksnap.pipeline.BookSnapPipeline`
implementing `dev.booksnap.harness.Pipeline`:

```kotlin
data class BoundingBox(val x: Int, val y: Int, val width: Int, val height: Int)
data class PageResult(
    val text: String,
    val textBounds: BoundingBox,
    val pageNumber: Int? = null,
    val pageNumberBounds: BoundingBox? = null,
)

interface Pipeline {
    suspend fun initialize(context: Context)
    suspend fun processImage(imagePath: String): PageResult
    suspend fun cleanup()
}
```

## Pre-installed Libraries

- **ML Kit Text Recognition** — `com.google.mlkit:text-recognition` (Latin)
- **OpenCV** — `org.opencv:opencv:4.9.0` (requires `OpenCVLoader.initLocal()` in `initialize()`)
- **Tesseract** — `com.rmtheis:tess-two:9.1.0`
- **GPUImage** — `jp.co.cyberagent.android:gpuimage:2.1.0`
- **ML Kit Language ID** — `com.google.mlkit:language-id:17.0.6`
- **Lucene Hunspell** — `org.apache.lucene:lucene-analyzers-common:8.11.4` (pure Java, works on Android). Standard Hunspell `.dic` + `.aff` files for en, fr, de, it, el are in Android `assets/hunspell/`. Load via `context.assets.open("hunspell/en.dic")` and `context.assets.open("hunspell/en.aff")` — they are NOT regular files on disk, only accessible through Android's `AssetManager` using the `Context` passed to `initialize()`. Use `org.apache.lucene.analysis.hunspell.Dictionary` to parse .dic/.aff with full affix expansion, then `Hunspell.spell()` to check words.

## Tips

- You can view the actual images by reading them from the data directory. This helps understand failure modes like curvature, skew, and facing-page bleed.
- The `arl diagnose` output shows expected vs actual text for each sample — compare them carefully to identify patterns.

## Research Directions

1. **Page detection + perspective correction** — OpenCV contour/Hough lines
2. **Dewarping curved pages** — straighten text near the spine
3. **Reading order** — sort text blocks top-to-bottom for correct assembly
4. **Page number extraction** — detect page numbers at top/bottom margins
5. **Facing-page filtering** — remove text fragments from adjacent pages
6. **Contrast enhancement** — CLAHE (be careful: binarization breaks ML Kit)
7. **OCR postprocessing** — fix common OCR substitution errors
