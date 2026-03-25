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

        // Extract page number from ALL blocks before any filtering
        val pageNumberResult = extractPageNumber(blocks, bitmap.height)

        // Filter out facing-page text by keeping only blocks from the dominant side
        val filtered = filterFacingPage(blocks, bitmap.width)

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

        // Remove any remaining page number text from lines
        val pageNum = pageNumberResult?.second
        val cleanedLines = if (pageNum != null) {
            sortedLines.filter { line ->
                val trimmed = line.text.trim()
                trimmed != pageNum.toString()
            }
        } else {
            sortedLines
        }

        val rawText = cleanedLines.joinToString("\n") { it.text }
        val text = rejoinHyphenatedWords(rawText)
        return PageResult(
            text = text,
            pageNumber = pageNum
        )
    }

    /**
     * Rejoin words that were hyphenated across line breaks.
     * "won-\nder" becomes "wonder"
     */
    private fun rejoinHyphenatedWords(text: String): String {
        // Match a hyphen at end of line followed by a newline and lowercase letter
        return text.replace(Regex("-\\n(\\p{Ll})")) { match ->
            match.groupValues[1]
        }
    }

    /**
     * Look for a page number in blocks and lines near the top or bottom margin.
     * Handles both standalone numbers and header lines like "110 ELENA FERRANTE" or "86 The Guns of August".
     * Returns the block and parsed page number, or null.
     */
    private fun extractPageNumber(blocks: List<OcrBlock>, imageHeight: Int): Pair<OcrBlock, Int>? {
        val marginFraction = 0.20 // top/bottom 20% of image
        val topThreshold = (imageHeight * marginFraction).toInt()
        val bottomThreshold = (imageHeight * (1.0 - marginFraction)).toInt()

        // Scan all individual lines across all blocks
        data class Candidate(val block: OcrBlock, val pageNum: Int, val priority: Int, val lineY: Int)
        val candidates = mutableListOf<Candidate>()

        for (block in blocks) {
            for (line in block.lines) {
                val lineY = line.boundingBox.centerY()
                val inMargin = lineY < topThreshold || lineY > bottomThreshold
                if (!inMargin) continue

                val lineText = line.text.trim()

                // Priority 1: line is just a number (standalone page number)
                val justNum = lineText.replace("[^0-9]".toRegex(), "")
                if (justNum.isNotEmpty() && justNum.length >= lineText.length * 0.6) {
                    val parsed = justNum.toIntOrNull()
                    if (parsed != null && parsed in 1..9999) {
                        candidates.add(Candidate(block, parsed, 1, lineY))
                        continue
                    }
                }

                // Priority 2: line starts with number (header like "110 ELENA FERRANTE")
                val startMatch = "^(\\d{1,4})\\s+\\D".toRegex().find(lineText)
                if (startMatch != null) {
                    val parsed = startMatch.groupValues[1].toIntOrNull()
                    if (parsed != null && parsed in 1..9999) {
                        candidates.add(Candidate(block, parsed, 2, lineY))
                        continue
                    }
                }

                // Priority 3: line ends with number (header like "VINELAND 81")
                val endMatch = "\\D\\s+(\\d{1,4})$".toRegex().find(lineText)
                if (endMatch != null) {
                    val parsed = endMatch.groupValues[1].toIntOrNull()
                    if (parsed != null && parsed in 1..9999) {
                        candidates.add(Candidate(block, parsed, 3, lineY))
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null

        // Sort by priority (lower is better), then prefer bottom of page
        val best = candidates.sortedWith(compareBy<Candidate> { it.priority }.thenByDescending { it.lineY }).first()
        return Pair(best.block, best.pageNum)
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
