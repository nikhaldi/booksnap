package dev.booksnap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface

/**
 * Baseline pipeline: loads the image and runs OCR with default settings.
 *
 * The research agent replaces this file (or adds preprocessing/postprocessing
 * around it) to improve OCR accuracy. The only contract is:
 *   - Class must be `dev.booksnap.pipeline.BookSnapPipeline`
 *   - Must implement `dev.booksnap.pipeline.Pipeline`
 *
 * The OCR engine is injected via [ocrEngine], defaulting to ML Kit.
 * Tests can inject a mock engine to exercise the full pipeline logic
 * without requiring a real OCR backend.
 */
class BookSnapPipeline(
    private val ocrEngine: OcrEngine? = null,
    private val languageDetector: LanguageDetector? = null,
) : Pipeline {

    private lateinit var engine: OcrEngine
    private var langDetector: LanguageDetector? = null

    override suspend fun initialize(context: Context, options: Map<String, Any>) {
        engine = ocrEngine ?: MlKitOcrEngine()
        langDetector = languageDetector ?: MlKitLanguageDetector()
    }

    override suspend fun processImage(imagePath: String): PageResult {
        val rawBitmap = BitmapFactory.decodeFile(imagePath)
            ?: return PageResult(text = "")
        val bitmap = applyExifRotation(imagePath, rawBitmap)

        val blocks = engine.recognize(bitmap)
        val text = blocks.joinToString("\n") { it.text }
        return PageResult(text = text)
    }

    private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override suspend fun cleanup() {
        engine.close()
        langDetector?.close()
    }
}
