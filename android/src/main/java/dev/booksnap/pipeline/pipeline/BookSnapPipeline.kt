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
        if (blocks.isEmpty()) return PageResult(text = "")

        // Filter out facing-page text by keeping only blocks from the dominant side
        val filtered = filterFacingPage(blocks, bitmap.width)

        // Extract page number from blocks near top or bottom margins
        val pageNumberResult = extractPageNumber(filtered, bitmap.height)

        // Remove page number block from text blocks
        val textBlocks = if (pageNumberResult != null) {
            filtered.filter { it !== pageNumberResult.first }
        } else {
            filtered
        }

        // Sort blocks top-to-bottom by their bounding box top edge
        val sorted = textBlocks.sortedBy { it.boundingBox.top }

        val text = sorted.joinToString("\n") { it.text }
        return PageResult(
            text = text,
            pageNumber = pageNumberResult?.second
        )
    }

    /**
     * Look for a page number: a small block near the top or bottom margin containing just a number.
     * Returns the block and parsed page number, or null.
     */
    private fun extractPageNumber(blocks: List<OcrBlock>, imageHeight: Int): Pair<OcrBlock, Int>? {
        val marginFraction = 0.15 // top/bottom 15% of image
        val topThreshold = (imageHeight * marginFraction).toInt()
        val bottomThreshold = (imageHeight * (1.0 - marginFraction)).toInt()

        val candidates = mutableListOf<Pair<OcrBlock, Int>>()

        for (block in blocks) {
            val box = block.boundingBox
            val text = block.text.trim()

            // Page numbers are typically short numeric strings
            val num = text.replace("[^0-9]".toRegex(), "")
            if (num.isEmpty()) continue
            // The text should be mostly numeric (allow some OCR noise)
            if (num.length < text.length * 0.5) continue
            // Page numbers are typically 1-4 digits
            val parsed = num.toIntOrNull() ?: continue
            if (parsed < 1 || parsed > 9999) continue

            // Must be in top or bottom margin
            val centerY = box.centerY()
            if (centerY < topThreshold || centerY > bottomThreshold) {
                candidates.add(Pair(block, parsed))
            }
        }

        // If multiple candidates, prefer the one closest to the bottom (most common location)
        return candidates.maxByOrNull { it.first.boundingBox.centerY() }
    }

    private fun filterFacingPage(blocks: List<OcrBlock>, imageWidth: Int): List<OcrBlock> {
        if (blocks.size <= 1) return blocks

        data class BlockInfo(val block: OcrBlock, val centerX: Int, val textLen: Int)
        val infos = blocks.map { block ->
            BlockInfo(block, block.boundingBox.centerX(), block.text.length)
        }

        // Find the spine: largest gap in block center X positions
        val sortedByX = infos.sortedBy { it.centerX }
        var bestGapPos = imageWidth / 2
        var bestGapSize = 0
        for (i in 0 until sortedByX.size - 1) {
            val gap = sortedByX[i + 1].centerX - sortedByX[i].centerX
            if (gap > bestGapSize) {
                bestGapSize = gap
                bestGapPos = (sortedByX[i].centerX + sortedByX[i + 1].centerX) / 2
            }
        }

        // Only split if the gap is significant (>5% of image width)
        if (bestGapSize < imageWidth * 0.05) {
            return blocks
        }

        // Calculate total text length on each side of the gap
        var leftLen = 0
        var rightLen = 0
        for (info in infos) {
            if (info.centerX < bestGapPos) leftLen += info.textLen
            else rightLen += info.textLen
        }

        val keepLeft = leftLen > rightLen

        return infos.filter { info ->
            if (keepLeft) info.centerX < bestGapPos else info.centerX >= bestGapPos
        }.map { it.block }
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
