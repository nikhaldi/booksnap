package dev.booksnap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

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
        OpenCVLoader.initLocal()
        engine = ocrEngine ?: MlKitOcrEngine()
        langDetector = languageDetector ?: MlKitLanguageDetector()
    }

    override suspend fun processImage(imagePath: String): PageResult {
        val rawBitmap = BitmapFactory.decodeFile(imagePath)
            ?: return PageResult(text = "")
        val bitmap = applyExifRotation(imagePath, rawBitmap)
        val (deskewed, deskewAngle) = deskewImage(bitmap)

        // Run OCR on original (deskewed) image for page number extraction
        val origBlocks = engine.recognize(deskewed)

        // Run OCR on denoised image for text content
        val denoised = denoiseImage(deskewed)
        val blocks = engine.recognize(denoised)

        // Extract page number from original OCR first, fallback to denoised OCR
        val pageNumberResult = (if (origBlocks.isNotEmpty()) {
            extractPageNumber(origBlocks, bitmap.height)
        } else null) ?: (if (blocks.isNotEmpty()) {
            extractPageNumber(blocks, bitmap.height)
        } else null)

        if (blocks.isEmpty()) return PageResult(text = "", pageNumber = pageNumberResult?.pageNum)

        // Filter out facing-page text by keeping only blocks from the dominant side
        val filtered = filterFacingPage(blocks, bitmap.width)

        // Don't try to remove the page number block (objects differ between OCR runs)
        // The line-level page number text removal in cleanedLines handles this
        val textBlocks = filtered

        // Extract all lines from all blocks
        val allLines = textBlocks.flatMap { it.lines }

        // Filter facing-page bleed at line level
        val filteredLines = filterFacingPageLines(allLines, bitmap.width)

        // Remove very short lines that are likely OCR noise (bleed-through, artifacts)
        val denoised = filteredLines.filter { line ->
            val text = line.text.trim()
            // Keep lines with at least 4 characters, or lines that are just a number (could be page num)
            text.length >= 4 || text.toIntOrNull() != null
        }

        // Sort by Y position for correct reading order
        val sortedLines = denoised.sortedBy { it.boundingBox.top }

        // Remove page number lines and running header lines from text
        val pageNum = pageNumberResult?.pageNum
        val imageHeight = bitmap.height
        val marginTop = (imageHeight * 0.15).toInt()
        val marginBottom = (imageHeight * 0.85).toInt()

        // Detect running header: first line that is in top margin and much shorter than body lines
        val medianTextLen = sortedLines.map { it.text.length }.sorted().let {
            if (it.size >= 3) it[it.size / 2] else 100
        }

        val cleanedLines = sortedLines.filter { line ->
            val trimmed = line.text.trim()
            val lineY = line.boundingBox.centerY()
            val inMargin = lineY < marginTop || lineY > marginBottom

            // Remove standalone page number lines
            if (pageNum != null && trimmed == pageNum.toString()) return@filter false

            // Remove running header lines in margins
            if (inMargin && trimmed.length < 50) {
                val hasDigit = trimmed.any { it.isDigit() }
                val isAllCaps = trimmed.filter { it.isLetter() }.let { letters ->
                    letters.isNotEmpty() && letters == letters.uppercase()
                }
                val isTitleCase = trimmed.split("\\s+".toRegex()).all { word ->
                    word.firstOrNull()?.isUpperCase() == true || word.all { !it.isLetter() }
                }
                // Remove if: has digit + is all-caps/title-case, OR short all-caps/title-case in top margin
                if (hasDigit && (isAllCaps || isTitleCase)) return@filter false
                if ((isAllCaps || isTitleCase) && lineY < marginTop && trimmed.length < 35) return@filter false
                // Remove short header-like lines in top margin that are much shorter than body text
                if (lineY < marginTop && trimmed.length < medianTextLen * 0.5 && trimmed.length < 40) {
                    return@filter false
                }
                // Remove short noise lines in bottom margin that are much shorter than body text
                if (lineY > marginBottom && trimmed.length < medianTextLen * 0.4 && trimmed.length < 30) {
                    return@filter false
                }
            }

            true
        }

        val paragraphs = joinLinesIntoParagraphs(cleanedLines)
        val postProcessed = splitDialogueParagraphs(paragraphs)
        val text = rejoinHyphenatedWords(postProcessed)

        // Compute text bounds as union of all cleaned line bounding boxes
        val rawTextBounds = computeUnionBounds(cleanedLines.map { it.boundingBox })
        val textBounds = undeskewBounds(rawTextBounds, deskewAngle, bitmap.width, bitmap.height)

        // Compute page number bounds from the matched line
        val pageNumberBounds = pageNumberResult?.line?.boundingBox?.let {
            val raw = BoundingBox(it.left, it.top, it.width(), it.height())
            undeskewBounds(raw, deskewAngle, bitmap.width, bitmap.height)
        }

        // Final fallback: extract page number from the assembled text itself
        val finalPageNum = pageNum ?: extractPageNumberFromText(text)

        return PageResult(
            text = text,
            textBounds = textBounds,
            pageNumber = finalPageNum,
            pageNumberBounds = pageNumberBounds,
        )
    }

    /**
     * Try to extract a page number from the end of the assembled text.
     * Looks for a standalone number on the last line.
     */
    private fun extractPageNumberFromText(text: String): Int? {
        val lines = text.trimEnd().split("\n")
        if (lines.isEmpty()) return null
        // Check last line for standalone number
        val lastLine = lines.last().trim()
        val num = lastLine.toIntOrNull()
        if (num != null && num in 1..9999) return num
        // Check first line for standalone number
        val firstLine = lines.first().trim()
        val firstNum = firstLine.toIntOrNull()
        if (firstNum != null && firstNum in 1..9999) return firstNum
        return null
    }

    /**
     * Join consecutive visual lines into paragraphs.
     * Uses vertical gap between lines to detect paragraph breaks.
     * Lines within the same paragraph are joined with a space.
     */
    private fun joinLinesIntoParagraphs(lines: List<OcrLine>): String {
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0].text

        // Calculate gaps between consecutive lines
        val gaps = mutableListOf<Int>()
        for (i in 0 until lines.size - 1) {
            val currentBottom = lines[i].boundingBox.bottom
            val nextTop = lines[i + 1].boundingBox.top
            gaps.add(nextTop - currentBottom)
        }

        // Calculate median line height for scale reference
        val lineHeights = lines.map { it.boundingBox.bottom - it.boundingBox.top }
        val medianHeight = if (lineHeights.isNotEmpty()) {
            lineHeights.sorted()[lineHeights.size / 2]
        } else {
            30
        }

        // Calculate right edges to detect short lines (paragraph endings)
        val rightEdges = lines.map { it.boundingBox.right }
        val maxRightEdge = rightEdges.maxOrNull() ?: 0
        // A short line ends significantly before the right margin
        val shortLineThreshold = maxRightEdge * 0.85

        // A paragraph break is when:
        // 1. The gap is significantly larger than the median line height, OR
        // 2. The previous line is "short" (doesn't fill the full column width)
        val paragraphGapThreshold = medianHeight * 0.8

        // Calculate left margin for indentation detection
        val leftEdges = lines.mapNotNull { it.boundingBox?.left }
        val sortedLeftEdges = leftEdges.sorted()
        // Use 25th percentile as the base left margin (most lines start here)
        val baseLeftMargin = if (sortedLeftEdges.size >= 4) {
            sortedLeftEdges[sortedLeftEdges.size / 4]
        } else {
            sortedLeftEdges.firstOrNull() ?: 0
        }
        // Indentation threshold: line starts more than 1.5x median height to the right of base
        val indentThreshold = baseLeftMargin + (medianHeight * 1.5).toInt()

        val result = StringBuilder()
        result.append(lines[0].text)

        for (i in 1 until lines.size) {
            val gap = gaps[i - 1]
            val prevRight = lines[i - 1].boundingBox.right
            val prevIsShort = prevRight < shortLineThreshold
            val prevText = lines[i - 1].text
            // Only treat short lines as paragraph endings if they end with punctuation
            // This avoids false breaks from truncated lines near the book spine
            val endsWithPunctuation = prevText.trimEnd().let { t ->
                val last = t.lastOrNull()
                last != null && ".!?\"')>\u00BB\u2014\u201D\u2019\u2026".contains(last)
            }

            // Detect dialogue/quote paragraph starts
            val currText = lines[i].text.trimStart()
            val startsWithQuote = currText.startsWith("\u00AB") || // «
                currText.startsWith("\u201C") || // "
                currText.startsWith("\u2018") || // '
                currText.startsWith("\u00BB")    // » (used as opening quote in German)

            // Detect indentation (new paragraph starts indented)
            val currLeft = lines[i].boundingBox?.left ?: baseLeftMargin
            val isIndented = currLeft > indentThreshold && endsWithPunctuation

            if (gap > paragraphGapThreshold || (prevIsShort && endsWithPunctuation) ||
                (startsWithQuote && endsWithPunctuation) || isIndented) {
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
     * Split text at dialogue boundaries that were missed by line-level detection.
     * Handles patterns like closing-quote + opening-quote within same line.
     */
    private fun splitDialogueParagraphs(text: String): String {
        var result = text
        // Split after closing guillemet/quote followed by sentence start
        // e.g., "...Pasquale». Restammo" -> "...Pasquale».\nRestammo"
        result = result.replace(Regex("(\u00BB[.!?]*) (\\p{Lu})")) { match ->
            "${match.groupValues[1]}\n${match.groupValues[2]}"
        }
        // Split before opening guillemet after colon
        // e.g., "...disse cupo: «Sì" -> "...disse cupo:\n«Sì"
        result = result.replace(Regex(": (\u00AB)")) { match ->
            ":\n${match.groupValues[1]}"
        }
        // Same for English-style quotes: closing " followed by capital
        result = result.replace(Regex("(\u201D[.!?]*) (\\p{Lu})")) { match ->
            "${match.groupValues[1]}\n${match.groupValues[2]}"
        }
        // Colon followed by opening "
        result = result.replace(Regex(": (\u201C)")) { match ->
            ":\n${match.groupValues[1]}"
        }
        return result
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
        // Fix missing apostrophes in common contractions
        val contractions = mapOf(
            "dont" to "don't", "didnt" to "didn't", "doesnt" to "doesn't",
            "isnt" to "isn't", "wasnt" to "wasn't", "couldnt" to "couldn't",
            "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "cant" to "can't",
            "wont" to "won't", "hasnt" to "hasn't", "havent" to "haven't",
            "hadnt" to "hadn't", "arent" to "aren't", "werent" to "weren't",
            "youre" to "you're", "theyre" to "they're", "thats" to "that's",
            "whats" to "what's", "heres" to "here's", "theres" to "there's",
            "lets" to "let's", "wheres" to "where's", "whos" to "who's",
        )
        result = result.replace(Regex("\\b(\\w+)\\b")) { match ->
            contractions[match.value] ?: match.value
        }
        // Fix common OCR substitutions
        result = result.replace("--", "\u2014") // double hyphen to em dash
        // Space-hyphen-space between words is typically an em dash
        result = result.replace(Regex("(?<=\\w) - (?=\\w)"), " \u2014 ")
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
    private data class PageNumberResult(val block: OcrBlock, val line: OcrLine, val pageNum: Int)

    private fun extractPageNumber(blocks: List<OcrBlock>, imageHeight: Int): PageNumberResult? {
        val marginFraction = 0.20 // top/bottom 20% of image
        val topThreshold = (imageHeight * marginFraction).toInt()
        val bottomThreshold = (imageHeight * (1.0 - marginFraction)).toInt()

        // Scan all individual lines across all blocks
        data class Candidate(val block: OcrBlock, val line: OcrLine, val pageNum: Int, val priority: Int, val lineY: Int)
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
                        candidates.add(Candidate(block, line, parsed, 1, lineY))
                        continue
                    }
                }

                // Priority 2: line starts with number (header like "110 ELENA FERRANTE")
                val startMatch = "^(\\d{1,4})\\s+\\D".toRegex().find(lineText)
                if (startMatch != null) {
                    val parsed = startMatch.groupValues[1].toIntOrNull()
                    if (parsed != null && parsed in 1..9999) {
                        candidates.add(Candidate(block, line, parsed, 2, lineY))
                        continue
                    }
                }

                // Priority 3: line ends with number (header like "VINELAND 81")
                val endMatch = "\\D\\s+(\\d{1,4})$".toRegex().find(lineText)
                if (endMatch != null) {
                    val parsed = endMatch.groupValues[1].toIntOrNull()
                    if (parsed != null && parsed in 1..9999) {
                        candidates.add(Candidate(block, line, parsed, 3, lineY))
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null

        // Sort by priority (lower is better), then prefer bottom of page
        val best = candidates.sortedWith(compareBy<Candidate> { it.priority }.thenByDescending { it.lineY }).first()
        return PageNumberResult(best.block, best.line, best.pageNum)
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

        val totalLen = leftLen + rightLen
        val minorSideLen = minOf(leftLen, rightLen)
        // Only split if the minor side has substantial text (>15% of total)
        // This avoids falsely splitting when a few lines are slightly offset
        if (totalLen == 0 || minorSideLen.toDouble() / totalLen < 0.15) {
            return lines
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

    private fun denoiseImage(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        // Convert RGBA to BGR for bilateral filter
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
        // Contrast stretching: normalize each channel to use full dynamic range
        val normalized = Mat()
        Core.normalize(bgr, normalized, 0.0, 255.0, Core.NORM_MINMAX)
        bgr.release()
        val filtered = Mat()
        // Bilateral filter: preserves edges while smoothing noise
        Imgproc.bilateralFilter(normalized, filtered, 11, 100.0, 100.0)
        normalized.release()
        // Apply mild unsharp mask to enhance text edges after smoothing
        val blurred = Mat()
        Imgproc.GaussianBlur(filtered, blurred, Size(0.0, 0.0), 3.0)
        val sharpened = Mat()
        Core.addWeighted(filtered, 1.5, blurred, -0.5, 0.0, sharpened)
        blurred.release()
        filtered.release()
        // Convert back to RGBA
        val rgba = Mat()
        Imgproc.cvtColor(sharpened, rgba, Imgproc.COLOR_BGR2RGBA)
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)
        mat.release()
        sharpened.release()
        rgba.release()
        return result
    }

    private data class DeskewResult(val bitmap: Bitmap, val angleDegrees: Double)

    private fun deskewImage(bitmap: Bitmap): DeskewResult {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        // Edge detection
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0, 3)

        // Detect lines using Hough transform
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 100, 100.0, 20.0)

        if (lines.rows() == 0) {
            gray.release()
            edges.release()
            lines.release()
            mat.release()
            return DeskewResult(bitmap, 0.0)
        }

        // Calculate median angle of near-horizontal lines
        val angles = mutableListOf<Double>()
        for (i in 0 until lines.rows()) {
            val line = lines.get(i, 0)
            val dx = line[2] - line[0]
            val dy = line[3] - line[1]
            val angle = Math.toDegrees(Math.atan2(dy, dx))
            // Only consider near-horizontal lines (within 15 degrees)
            if (Math.abs(angle) < 15) {
                angles.add(angle)
            }
        }

        gray.release()
        edges.release()
        lines.release()

        if (angles.isEmpty()) {
            mat.release()
            return DeskewResult(bitmap, 0.0)
        }

        val sortedAngles = angles.sorted()
        val medianAngle = sortedAngles[sortedAngles.size / 2]

        // Only deskew if angle is small but noticeable
        if (Math.abs(medianAngle) < 0.3 || Math.abs(medianAngle) > 10) {
            mat.release()
            return DeskewResult(bitmap, 0.0)
        }

        // Rotate to correct skew
        val center = Point(mat.cols() / 2.0, mat.rows() / 2.0)
        val rotMat = Imgproc.getRotationMatrix2D(center, medianAngle, 1.0)
        val rotated = Mat()
        Imgproc.warpAffine(mat, rotated, rotMat, Size(mat.cols().toDouble(), mat.rows().toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rotated, result)

        mat.release()
        rotMat.release()
        rotated.release()

        return DeskewResult(result, medianAngle)
    }

    /**
     * Map a bounding box from deskewed image coordinates back to original image coordinates
     * by applying the inverse rotation around the image center.
     */
    private fun undeskewBounds(bounds: BoundingBox, angleDegrees: Double, imageWidth: Int, imageHeight: Int): BoundingBox {
        if (angleDegrees == 0.0) return bounds
        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0
        val rad = Math.toRadians(-angleDegrees) // reverse: rotate back by negating the deskew angle
        val cos = Math.cos(rad)
        val sin = Math.sin(rad)

        // Rotate the four corners of the bounding box
        val corners = listOf(
            bounds.x.toDouble() to bounds.y.toDouble(),
            (bounds.x + bounds.width).toDouble() to bounds.y.toDouble(),
            (bounds.x + bounds.width).toDouble() to (bounds.y + bounds.height).toDouble(),
            bounds.x.toDouble() to (bounds.y + bounds.height).toDouble(),
        )
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE
        for ((px, py) in corners) {
            val dx = px - cx
            val dy = py - cy
            val rx = cos * dx + sin * dy + cx
            val ry = -sin * dx + cos * dy + cy
            if (rx < minX) minX = rx
            if (ry < minY) minY = ry
            if (rx > maxX) maxX = rx
            if (ry > maxY) maxY = ry
        }
        return BoundingBox(minX.toInt(), minY.toInt(), (maxX - minX).toInt(), (maxY - minY).toInt())
    }

    private fun computeUnionBounds(rects: List<android.graphics.Rect>): BoundingBox {
        if (rects.isEmpty()) return BoundingBox(0, 0, 0, 0)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (r in rects) {
            if (r.left < minX) minX = r.left
            if (r.top < minY) minY = r.top
            if (r.right > maxX) maxX = r.right
            if (r.bottom > maxY) maxY = r.bottom
        }
        return BoundingBox(minX, minY, maxX - minX, maxY - minY)
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
