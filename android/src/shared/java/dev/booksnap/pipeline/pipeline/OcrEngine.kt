package dev.booksnap.pipeline

import android.graphics.Bitmap
import android.graphics.Rect

// Platform-agnostic OCR result types and engine interface.
//
// Decouples the pipeline logic (filtering, ordering, assembly, spell check)
// from the OCR engine (ML Kit, Tesseract, etc.), making the pipeline
// testable on JVM with mocked OCR output.

/** A single line of recognized text with its bounding box. */
data class OcrLine(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float = 1.0f,
)

/** A block of recognized text containing one or more lines. */
data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val lines: List<OcrLine>,
    val confidence: Float = 1.0f,
)

/** Interface for OCR engines. Implementations wrap platform-specific APIs. */
interface OcrEngine {
    /** Recognize text in a bitmap. Returns blocks in the engine's native order. */
    suspend fun recognize(bitmap: Bitmap): List<OcrBlock>

    /** Release resources. Called once after all images are processed. */
    fun close() {}
}
