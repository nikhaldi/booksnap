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

        // Extract all lines from all blocks
        val allLines = textBlocks.flatMap { it.lines }

        // Filter facing-page bleed at line level
        val filteredLines = filterFacingPageLines(allLines, bitmap.width)

        // Sort by Y position for correct reading order
        val sortedLines = filteredLines.sortedBy { it.boundingBox.top }

        val text = sortedLines.joinToString("\n") { it.text }
        return PageResult(
            text = text,
            pageNumber = pageNumberResult?.second
        )
    }

    /**
     * Look for a page number in blocks and lines near the top or bottom margin.
     * Handles both standalone numbers and header lines like "110 ELENA FERRANTE" or "86 The Guns of August".
     * Returns the block and parsed page number, or null.
     */
    private fun extractPageNumber(blocks: List<OcrBlock>, imageHeight: Int): Pair<OcrBlock, Int>? {
        val marginFraction = 0.15 // top/bottom 15% of image
        val topThreshold = (imageHeight * marginFraction).toInt()
        val bottomThreshold = (imageHeight * (1.0 - marginFraction)).toInt()

        // Priority 1: standalone number blocks in margins
        val standaloneCandidates = mutableListOf<Pair<OcrBlock, Int>>()
        // Priority 2: lines starting/ending with a number in margins (headers)
        val headerCandidates = mutableListOf<Pair<OcrBlock, Int>>()

        for (block in blocks) {
            val box = block.boundingBox
            val centerY = box.centerY()
            val inMargin = centerY < topThreshold || centerY > bottomThreshold

            if (!inMargin) continue

            val text = block.text.trim()

            // Check if the whole block is just a number
            val num = text.replace("[^0-9]".toRegex(), "")
            if (num.isNotEmpty() && num.length >= text.length * 0.5) {
                val parsed = num.toIntOrNull()
                if (parsed != null && parsed in 1..9999) {
                    standaloneCandidates.add(Pair(block, parsed))
                    continue
                }
            }

            // Check first line for header pattern: starts with number like "110 ELENA FERRANTE"
            val firstLine = block.lines.firstOrNull()
            if (firstLine != null) {
                val lineY = firstLine.boundingBox.centerY()
                if (lineY < topThreshold || lineY > bottomThreshold) {
                    val lineText = firstLine.text.trim()
                    val startMatch = "^(\\d{1,4})\\s+\\D".toRegex().find(lineText)
                    if (startMatch != null) {
                        val parsed = startMatch.groupValues[1].toIntOrNull()
                        if (parsed != null && parsed in 1..9999) {
                            headerCandidates.add(Pair(block, parsed))
                            continue
                        }
                    }
                }
            }

            // Check last line for pattern: ends with number like "The Guns of August 86"
            val lastLine = block.lines.lastOrNull()
            if (lastLine != null) {
                val lineY = lastLine.boundingBox.centerY()
                if (lineY < topThreshold || lineY > bottomThreshold) {
                    val lineText = lastLine.text.trim()
                    val endMatch = "\\D\\s+(\\d{1,4})$".toRegex().find(lineText)
                    if (endMatch != null) {
                        val parsed = endMatch.groupValues[1].toIntOrNull()
                        if (parsed != null && parsed in 1..9999) {
                            headerCandidates.add(Pair(block, parsed))
                        }
                    }
                }
            }
        }

        // Prefer standalone numbers, then header-embedded numbers
        if (standaloneCandidates.isNotEmpty()) {
            return standaloneCandidates.maxByOrNull { it.first.boundingBox.centerY() }
        }
        return headerCandidates.firstOrNull()
    }

    private fun filterFacingPageLines(lines: List<OcrLine>, imageWidth: Int): List<OcrLine> {
        if (lines.size <= 1) return lines

        data class LineInfo(val line: OcrLine, val centerX: Int, val textLen: Int)
        val infos = lines.map { line ->
            LineInfo(line, line.boundingBox.centerX(), line.text.length)
        }

        // Find the spine using the largest gap in line center X positions
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

        // Only split if the gap is significant (>8% of image width)
        if (bestGapSize < imageWidth * 0.08) {
            return lines
        }

        // Keep the side with more total text
        var leftLen = 0
        var rightLen = 0
        for (info in infos) {
            if (info.centerX < bestGapPos) leftLen += info.textLen
            else rightLen += info.textLen
        }

        val keepLeft = leftLen > rightLen
        return infos.filter { info ->
            if (keepLeft) info.centerX < bestGapPos else info.centerX >= bestGapPos
        }.map { it.line }
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
