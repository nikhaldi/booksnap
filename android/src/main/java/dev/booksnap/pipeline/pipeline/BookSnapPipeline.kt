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

        // Remove page number lines and running header lines from text
        val pageNum = pageNumberResult?.second
        val imageHeight = bitmap.height
        val marginTop = (imageHeight * 0.15).toInt()
        val marginBottom = (imageHeight * 0.85).toInt()
        val cleanedLines = sortedLines.filter { line ->
            val trimmed = line.text.trim()
            val lineY = line.boundingBox?.centerY() ?: (imageHeight / 2)
            val inMargin = lineY < marginTop || lineY > marginBottom

            // Remove standalone page number lines
            if (pageNum != null && trimmed == pageNum.toString()) return@filter false

            // Remove running header lines: short text in margins containing digits
            // Headers typically have book title + page number, short overall
            if (inMargin && trimmed.length < 40) {
                val hasDigit = trimmed.any { it.isDigit() }
                val isAllCapsOrTitle = trimmed.uppercase() == trimmed ||
                    trimmed.split("\\s+".toRegex()).all { word ->
                        word.firstOrNull()?.isUpperCase() == true || word.all { !it.isLetter() }
                    }
                if (hasDigit && isAllCapsOrTitle) return@filter false
            }

            true
        }

        val paragraphs = joinLinesIntoParagraphs(cleanedLines)
        val text = rejoinHyphenatedWords(paragraphs)
        return PageResult(
            text = text,
            pageNumber = pageNum
        )
    }

    /**
     * Join consecutive visual lines into paragraphs.
     * Uses vertical gap between lines to detect paragraph breaks.
     * Lines within the same paragraph are joined with a space.
     */
    private fun joinLinesIntoParagraphs(lines: List<Text.Line>): String {
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0].text

        // Calculate gaps between consecutive lines
        val gaps = mutableListOf<Int>()
        for (i in 0 until lines.size - 1) {
            val currentBottom = lines[i].boundingBox?.bottom ?: 0
            val nextTop = lines[i + 1].boundingBox?.top ?: 0
            gaps.add(nextTop - currentBottom)
        }

        // Calculate median line height for scale reference
        val lineHeights = lines.mapNotNull { it.boundingBox?.let { box -> box.bottom - box.top } }
        val medianHeight = if (lineHeights.isNotEmpty()) {
            lineHeights.sorted()[lineHeights.size / 2]
        } else {
            30
        }

        // Calculate right edges to detect short lines (paragraph endings)
        val rightEdges = lines.mapNotNull { it.boundingBox?.right }
        val maxRightEdge = rightEdges.maxOrNull() ?: 0
        // A short line ends significantly before the right margin
        val shortLineThreshold = maxRightEdge * 0.85

        // A paragraph break is when:
        // 1. The gap is significantly larger than the median line height, OR
        // 2. The previous line is "short" (doesn't fill the full column width)
        val paragraphGapThreshold = medianHeight * 0.8

        val result = StringBuilder()
        result.append(lines[0].text)

        for (i in 1 until lines.size) {
            val gap = gaps[i - 1]
            val prevRight = lines[i - 1].boundingBox?.right ?: maxRightEdge
            val prevIsShort = prevRight < shortLineThreshold
            val prevText = lines[i - 1].text

            if (gap > paragraphGapThreshold || prevIsShort) {
                result.append("\n")
            } else if (prevText.endsWith("-")) {
                // Don't add space - the hyphen rejoin will handle this
                result.append("\n")
            } else {
                result.append(" ")
            }
            result.append(lines[i].text)
        }

        return result.toString()
    }

    /**
     * Rejoin words that were hyphenated across line breaks.
     * "won-\nder" becomes "wonder"
     */
    private fun rejoinHyphenatedWords(text: String): String {
        // Match a hyphen at end of line followed by a newline and lowercase letter
        var result = text.replace(Regex("-\\n(\\p{Ll})")) { match ->
            match.groupValues[1]
        }
        // Fix common OCR substitutions
        result = result.replace("--", "—") // double hyphen to em dash
        result = result.replace("«", "«").replace("»", "»") // already correct, skip
        // Fix guillemets: < and > used as quote marks in French/German text
        result = result.replace(Regex("(?<=\\s)<(?=\\s)"), "«")
            .replace(Regex("(?<=\\s)>(?=\\s)"), "»")
        // Also handle << and >> patterns
        result = result.replace("<<", "«").replace(">>", "»")
        return result
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
