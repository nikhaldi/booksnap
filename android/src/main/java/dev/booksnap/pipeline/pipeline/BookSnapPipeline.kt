package dev.booksnap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.tasks.await
import org.apache.lucene.analysis.hunspell.Hunspell
import org.apache.lucene.store.ByteBuffersDirectory
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.BufferedInputStream
import org.apache.lucene.analysis.hunspell.Dictionary as HunspellDictionary

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
    private val hunspellCheckers = mutableMapOf<String, Hunspell>()
    private var spellCheckEnabled = true

    override suspend fun initialize(
        context: Context,
        options: Map<String, Any>,
    ) {
        spellCheckEnabled = options["spellCheck"] as? Boolean ?: true
        OpenCvCompat.init()
        engine = ocrEngine ?: MlKitOcrEngine()
        langDetector = languageDetector ?: MlKitLanguageDetector()
        if (spellCheckEnabled) {
            val langsStr = options["hunspellLangs"] as? String ?: ""
            val langs = langsStr.split(",").filter { it.isNotBlank() }
            for (lang in langs) {
                try {
                    val affStream = BufferedInputStream(context.assets.open("hunspell/$lang.aff"))
                    val dicStream = BufferedInputStream(context.assets.open("hunspell/$lang.dic"))
                    val tempDir = ByteBuffersDirectory()
                    val dict = HunspellDictionary(tempDir, "hunspell_$lang", affStream, dicStream)
                    hunspellCheckers[lang] = Hunspell(dict)
                    affStream.close()
                    dicStream.close()
                } catch (e: Exception) {
                    // skip unavailable dictionaries
                }
            }
        }
    }

    override suspend fun processImage(imagePath: String): PageResult {
        val rawBitmap =
            BitmapFactory.decodeFile(imagePath)
                ?: return PageResult(text = "")
        val bitmap = applyExifRotation(imagePath, rawBitmap)
        val (deskewed, deskewAngle) = deskewImage(bitmap)

        // Run OCR on original (deskewed) image for page number extraction
        val origBlocks = engine.recognize(deskewed)

        // Run OCR on denoised image for text content
        val denoisedBmp = denoiseImage(deskewed)
        val blocks = engine.recognize(denoisedBmp)

        // Extract page number from original OCR first, fallback to denoised OCR
        val pageNumberResult =
            (
                if (origBlocks.isNotEmpty()) {
                    extractPageNumber(origBlocks, bitmap.height)
                } else {
                    null
                }
            ) ?: (
                if (blocks.isNotEmpty()) {
                    extractPageNumber(blocks, bitmap.height)
                } else {
                    null
                }
            )

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
        val denoised =
            filteredLines.filter { line ->
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
        val medianTextLen =
            sortedLines.map { it.text.length }.sorted().let {
                if (it.size >= 3) it[it.size / 2] else 100
            }

        // Find the top Y of the first substantial body text line
        // Short lines ABOVE this are headers; short lines BELOW are body text
        val firstBodyLineTop =
            sortedLines
                .firstOrNull { it.text.length >= medianTextLen * 0.5 }
                ?.boundingBox
                ?.top ?: 0

        val cleanedLines =
            sortedLines.filter { line ->
                val trimmed = line.text.trim()
                val lineY = line.boundingBox.centerY()
                val lineTop = line.boundingBox.top
                val inMargin = lineY < marginTop || lineY > marginBottom

                // Remove standalone page number lines
                if (pageNum != null && trimmed == pageNum.toString()) return@filter false

                // Remove running header lines in margins
                if (inMargin && trimmed.length < 50) {
                    val hasDigit = trimmed.any { it.isDigit() }
                    val isAllCaps =
                        trimmed.filter { it.isLetter() }.let { letters ->
                            letters.isNotEmpty() && letters == letters.uppercase()
                        }
                    val isTitleCase =
                        trimmed.split("\\s+".toRegex()).all { word ->
                            word.firstOrNull()?.isUpperCase() == true || word.all { !it.isLetter() }
                        }
                    // Remove if: has digit + is all-caps/title-case, OR short all-caps/title-case in top margin
                    if (hasDigit && (isAllCaps || isTitleCase)) return@filter false
                    if ((isAllCaps || isTitleCase) && lineY < marginTop && trimmed.length < 35) return@filter false
                    // Remove short header-like lines in top margin that are much shorter than body text
                    // Only remove if the line is AT or ABOVE the first substantial body text line
                    if (lineY < marginTop && lineTop <= firstBodyLineTop && trimmed.length < medianTextLen * 0.5 && trimmed.length < 40) {
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
        val headerResult = removeHeaderPrefix(postProcessed, pageNum)
        // Detect language for spell correction and language-specific postprocessing
        val textLang =
            try {
                langDetector?.identifyLanguage(headerResult.text.take(500)) ?: "und"
            } catch (e: Exception) {
                "und"
            }

        val rawText = rejoinHyphenatedWords(headerResult.text, textLang)
        val text =
            if (spellCheckEnabled && textLang != "und" && hunspellCheckers.containsKey(textLang)) {
                spellCorrectText(rawText, hunspellCheckers[textLang]!!)
            } else {
                rawText
            }

        // Compute text bounds as union of all cleaned line bounding boxes
        val rawTextBounds = computeUnionBounds(cleanedLines.map { it.boundingBox })
        val textBounds = undeskewBounds(rawTextBounds, deskewAngle, bitmap.width, bitmap.height)

        // Compute page number bounds from the matched line
        val pageNumberBounds =
            pageNumberResult?.line?.boundingBox?.let {
                val raw = BoundingBox(it.left, it.top, it.width(), it.height())
                undeskewBounds(raw, deskewAngle, bitmap.width, bitmap.height)
            }

        // Final fallback: extract page number from the assembled text itself
        val textPageResult = extractPageNumberFromText(text)
        val finalPageNum = headerResult.pageNum ?: textPageResult.pageNum
        // If page number came from text extraction, use the cleaned text (number stripped)
        val finalText =
            if (headerResult.pageNum == null && textPageResult.pageNum != null) {
                textPageResult.cleanedText
            } else {
                text
            }

        // If page number was found via text fallback but we have no bounds,
        // search the original ML Kit lines for the matching number's bounding box
        val finalPageNumberBounds =
            pageNumberBounds ?: if (finalPageNum != null) {
                val numStr = finalPageNum.toString()
                sortedLines.find { it.text.trim() == numStr }?.boundingBox?.let {
                    val raw = BoundingBox(it.left, it.top, it.width(), it.height())
                    undeskewBounds(raw, deskewAngle, bitmap.width, bitmap.height)
                }
            } else {
                null
            }

        return PageResult(
            text = finalText,
            textBounds = textBounds,
            pageNumber = finalPageNum,
            pageNumberBounds = finalPageNumberBounds,
        )
    }

    /**
     * Try to extract a page number from the end of the assembled text.
     * Looks for a standalone number on the last or first line.
     * Returns the page number and the text with the page number line removed.
     */
    private data class TextPageNumberResult(
        val pageNum: Int?,
        val cleanedText: String,
    )

    private fun extractPageNumberFromText(text: String): TextPageNumberResult {
        val lines = text.trimEnd().split("\n")
        if (lines.isEmpty()) return TextPageNumberResult(null, text)
        // Check last line for standalone number
        val lastLine = lines.last().trim()
        val num = lastLine.toIntOrNull()
        if (num != null && num in 1..9999) {
            return TextPageNumberResult(num, lines.dropLast(1).joinToString("\n"))
        }
        // Check first line for standalone number
        val firstLine = lines.first().trim()
        val firstNum = firstLine.toIntOrNull()
        if (firstNum != null && firstNum in 1..9999) {
            return TextPageNumberResult(firstNum, lines.drop(1).joinToString("\n"))
        }
        return TextPageNumberResult(null, text)
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
        val medianHeight =
            if (lineHeights.isNotEmpty()) {
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
        val leftEdges = lines.map { it.boundingBox.left }
        val sortedLeftEdges = leftEdges.sorted()
        // Use 25th percentile as the base left margin (most lines start here)
        val baseLeftMargin =
            if (sortedLeftEdges.size >= 4) {
                sortedLeftEdges[sortedLeftEdges.size / 4]
            } else {
                sortedLeftEdges.firstOrNull() ?: 0
            }
        // Indentation threshold: line starts more than 1.5x median height to the right of base
        val indentThreshold = baseLeftMargin + medianHeight

        val result = StringBuilder()
        result.append(lines[0].text)

        for (i in 1 until lines.size) {
            val gap = gaps[i - 1]
            val prevRight = lines[i - 1].boundingBox.right
            val prevIsShort = prevRight < shortLineThreshold
            val prevText = lines[i - 1].text
            // Only treat short lines as paragraph endings if they end with punctuation
            // This avoids false breaks from truncated lines near the book spine
            val endsWithPunctuation =
                prevText.trimEnd().let { t ->
                    val last = t.lastOrNull()
                    last != null && ".!?:\"')>\u00AB\u00BB\u2014\u201D\u2019\u2026".contains(last)
                }

            // Detect dialogue/quote paragraph starts
            val currText = lines[i].text.trimStart()
            val startsWithQuote =
                currText.startsWith("\u00AB") || // «
                    currText.startsWith("\u201C") || // "
                    currText.startsWith("\u2018") || // '
                    currText.startsWith("\u00BB") // » (used as opening quote in German)

            // Detect indentation (new paragraph starts indented)
            val currLeft = lines[i].boundingBox.left
            val isIndented = currLeft > indentThreshold && endsWithPunctuation

            if (gap > paragraphGapThreshold || (prevIsShort && endsWithPunctuation) ||
                (startsWithQuote && endsWithPunctuation) || isIndented
            ) {
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
     * Detect and remove running header text at the start of output text.
     * Returns pair of (cleaned text, detected page number or null).
     * Patterns: "TITLE 123 actual text..." or "Author Name 42 text..."
     */
    private data class HeaderCleanResult(
        val text: String,
        val pageNum: Int?,
    )

    private fun removeHeaderPrefix(
        text: String,
        existingPageNum: Int?,
    ): HeaderCleanResult {
        // Look for a number within first 60 chars preceded by title-like text
        val match = "^(.{1,55}?)(\\d{1,4})\\s".toRegex().find(text)
        if (match != null) {
            val prefix = match.groupValues[1].trim()
            val numStr = match.groupValues[2]
            val num = numStr.toIntOrNull()
            if (num != null && num in 1..9999 && prefix.isNotEmpty()) {
                val words = prefix.split("\\s+".toRegex())
                val isTitleLike =
                    words.size >= 2 &&
                        words.all { word ->
                            word.firstOrNull()?.isUpperCase() == true || word.all { !it.isLetter() }
                        }
                if (isTitleLike && prefix.length in 3..55) {
                    val afterHeader = text.substring(match.range.last + 1).trimStart()
                    val detectedPageNum = existingPageNum ?: num
                    return HeaderCleanResult(afterHeader, detectedPageNum)
                }
            }
        }
        // Also catch short header-only first lines (no page number) where
        // the following content starts with a lowercase letter (mid-sentence continuation)
        val nlPos = text.indexOf('\n')
        if (nlPos in 1..39) {
            val firstLine = text.substring(0, nlPos).trim()
            val afterNl = text.substring(nlPos + 1).trimStart()
            if (afterNl.isNotEmpty() && afterNl[0].isLowerCase() && firstLine.length < 35) {
                return HeaderCleanResult(afterNl, existingPageNum)
            }
        }
        return HeaderCleanResult(text, existingPageNum)
    }

    /**
     * Split text at dialogue boundaries that were missed by line-level detection.
     * Handles patterns like closing-quote + opening-quote within same line.
     */
    private fun splitDialogueParagraphs(text: String): String {
        var result = text
        // Split after closing guillemet/quote followed by sentence start
        // e.g., "...Pasquale». Restammo" -> "...Pasquale».\nRestammo"
        result =
            result.replace(Regex("(\u00BB[.!?]*) (\\p{Lu})")) { match ->
                "${match.groupValues[1]}\n${match.groupValues[2]}"
            }
        // Split before opening guillemet after colon (dialogue pattern)
        // Only when preceded by lowercase (dialogue: "disse cupo: «Sì")
        // Avoids splitting mid-sentence references like "Palais Ducal : « l'Escalier"
        result =
            result.replace(Regex("(\\p{Ll}): (\u00AB)")) { match ->
                "${match.groupValues[1]}:\n${match.groupValues[2]}"
            }
        // Same for English-style quotes: closing " followed by capital
        result =
            result.replace(Regex("(\u201D[.!?]*) (\\p{Lu})")) { match ->
                "${match.groupValues[1]}\n${match.groupValues[2]}"
            }
        // Colon followed by opening "
        result =
            result.replace(Regex(": (\u201C)")) { match ->
                ":\n${match.groupValues[1]}"
            }
        return result
    }

    /**
     * Rejoin words that were hyphenated across line breaks.
     * "won-\nder" becomes "wonder"
     */
    private fun rejoinHyphenatedWords(
        text: String,
        language: String = "und",
    ): String {
        // Match a hyphen at end of line followed by a newline and lowercase letter
        var result =
            text.replace(Regex("-\\n(\\p{Ll})")) { match ->
                match.groupValues[1]
            }
        // Fix missing apostrophes in common English contractions
        if (language == "en") {
            // Only contractions where the un-apostrophe'd form is never a valid word.
            // Excluded: cant, wont, lets, thats, whats, heres, theres, whos (all valid words)
            val contractions =
                mapOf(
                    "dont" to "don't",
                    "didnt" to "didn't",
                    "doesnt" to "doesn't",
                    "isnt" to "isn't",
                    "wasnt" to "wasn't",
                    "couldnt" to "couldn't",
                    "wouldnt" to "wouldn't",
                    "shouldnt" to "shouldn't",
                    "hasnt" to "hasn't",
                    "havent" to "haven't",
                    "hadnt" to "hadn't",
                    "arent" to "aren't",
                    "werent" to "weren't",
                    "youre" to "you're",
                    "theyre" to "they're",
                )
            result =
                result.replace(Regex("\\b(\\w+)\\b")) { match ->
                    contractions[match.value] ?: match.value
                }
        }
        // Fix common OCR substitutions
        result = result.replace("--", "\u2014") // double hyphen to em dash
        // Space-hyphen-space between words is typically an em dash
        result = result.replace(Regex("(?<=\\w) - (?=\\w)"), " \u2014 ")
        // Comma-space-hyphen pattern is en dash in German/French: ", – "
        result = result.replace(Regex(", -(?=\\s|\\p{L})"), ", \u2013 ")
        // Hyphen at start of line is en dash in French dialogue: "– Peux-tu"
        result = result.replace(Regex("(?<=\\n)- "), "\u2013 ")
        result = result.replace("«", "«").replace("»", "»") // already correct, skip
        // Fix guillemets: < and > used as quote marks in French/German text
        result =
            result
                .replace(Regex("(?<=\\s)<(?=\\s)"), "«")
                .replace(Regex("(?<=\\s)>(?=\\s)"), "»")
        // Also handle << and >> patterns
        result = result.replace("<<", "«").replace(">>", "»")
        // Fix < before uppercase letter (OCR reads « as <)
        result = result.replace(Regex("<(\\p{Lu})")) { "«${it.groupValues[1]}" }
        // Fix trailing > after punctuation or word (OCR reads » as >)
        result = result.replace(Regex("(\\p{L}[.!?]?)>(?=[.\\s\n]|$)")) { "${it.groupValues[1]}»" }
        // Remove duplicate » or > after »
        result = result.replace(Regex("»[>»]+"), "»")
        // Remove < or > inserted adjacent to guillemets (OCR noise)
        result = result.replace(Regex("[<>]+«"), "«")
        result = result.replace(Regex("»[<>]+"), "»")
        result = result.replace(Regex("[<>]+»"), "»")
        result = result.replace(Regex("«[<>]+"), "«")
        result = result.replace(Regex("(?<=\\s)>\\s*(?=»)"), "")
        result = result.replace(Regex("(?<=«)\\s*<(?=\\s)"), "")
        // Remove pipe characters (OCR noise, never appears in book text)
        result = result.replace("|", "")
        // Convert curly quotes to straight quotes (ground truth uses straight quotes)
        result = result.replace("\u201C", "\"") // " -> "
        result = result.replace("\u201D", "\"") // " -> "
        result = result.replace("\u2018", "'") // ' -> '
        result = result.replace("\u2019", "'")
        // Fix OCR dropping 'n' in n't contractions: "does't" → "doesn't"
        val knownNtContractions =
            setOf(
                "don't",
                "didn't",
                "doesn't",
                "isn't",
                "wasn't",
                "couldn't",
                "wouldn't",
                "shouldn't",
                "can't",
                "won't",
                "hasn't",
                "haven't",
                "hadn't",
                "aren't",
                "weren't",
            )
        result =
            result.replace(Regex("\\b(\\w+)'t\\b")) { match ->
                val withN = match.groupValues[1] + "n't"
                if (withN.lowercase() in knownNtContractions) withN else match.value
            }
        // Fix German OCR: 'fß' is commonly misread for 'ß' (e.g. dafß -> daß)
        result = result.replace("fß", "ß")
        // Fix Vietnamese-style diacritics misapplied to European text
        result = result.replace("\u1ED9", "\u00F4") // ộ -> ô
        result = result.replace("\u1ED3", "\u00F4") // ồ -> ô
        result = result.replace("\u1EA3", "\u00E4") // ả -> ä
        result = result.replace("\u1EA2", "\u00C4") // Ả -> Ä
        result = result.replace("\u1EBF", "\u00E9") // ế -> é
        // Fix French: 'oœ' is OCR artifact for 'œ' (e.g. coœur -> cœur)
        result = result.replace("oœ", "œ")
        // Fix wrong accent: í (i-acute) is rarely correct in en/fr/de/it text
        // OCR misapplies it instead of ì or plain i
        result = result.replace("í", "i")
        // Fix Italian: così is almost always accented (cosi is not a word)
        result = result.replace(Regex("\\bcosi\\b"), "cos\u00EC")
        // Fix French dropped apostrophes in common elisions
        result = result.replace(Regex("\\bquil\\b"), "qu'il")
        result = result.replace(Regex("\\bjen\\b"), "j'en")
        result = result.replace(Regex("\\bjai\\b"), "j'ai")
        result = result.replace(Regex("\\b[Cc]était\\b")) { "${it.value[0]}'était" }
        return result
    }

    /**
     * Look for a page number in blocks and lines near the top or bottom margin.
     * Handles both standalone numbers and header lines like "110 ELENA FERRANTE" or "86 The Guns of August".
     * Returns the block and parsed page number, or null.
     */
    private data class PageNumberResult(
        val block: OcrBlock,
        val line: OcrLine,
        val pageNum: Int,
    )

    private fun extractPageNumber(
        blocks: List<OcrBlock>,
        imageHeight: Int,
    ): PageNumberResult? {
        val marginFraction = 0.25 // top/bottom 25% of image
        val topThreshold = (imageHeight * marginFraction).toInt()
        val bottomThreshold = (imageHeight * (1.0 - marginFraction)).toInt()

        // Scan all individual lines across all blocks
        data class Candidate(
            val block: OcrBlock,
            val line: OcrLine,
            val pageNum: Int,
            val priority: Int,
            val lineY: Int,
        )
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
        val sorted = candidates.sortedWith(compareBy<Candidate> { it.priority }.thenByDescending { it.lineY })
        val best = sorted.first()

        // If the best candidate is a small standalone number (< 30) and there's a larger
        // header-embedded candidate, prefer the header one (small standalone numbers are
        // often OCR noise from non-text elements like game boards or artifacts)
        if (best.priority == 1 && best.pageNum < 30) {
            val headerCandidate = sorted.firstOrNull { it.priority > 1 && it.pageNum >= 30 }
            if (headerCandidate != null) {
                return PageNumberResult(headerCandidate.block, headerCandidate.line, headerCandidate.pageNum)
            }
        }

        return PageNumberResult(best.block, best.line, best.pageNum)
    }

    private fun filterFacingPageLines(
        lines: List<OcrLine>,
        imageWidth: Int,
    ): List<OcrLine> {
        if (lines.size <= 1) return lines

        data class LineInfo(
            val line: OcrLine,
            val centerX: Int,
            val textLen: Int,
        )
        val infos =
            lines.map { line ->
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
            if (info.centerX < bestGapPos) {
                leftLen += info.textLen
            } else {
                rightLen += info.textLen
            }
        }

        val totalLen = leftLen + rightLen
        val minorSideLen = minOf(leftLen, rightLen)
        // Only split if the minor side has substantial text (>15% of total)
        // This avoids falsely splitting when a few lines are slightly offset
        if (totalLen == 0 || minorSideLen.toDouble() / totalLen < 0.15) {
            return lines
        }

        val keepLeft = leftLen > rightLen
        return infos
            .filter { info ->
                if (keepLeft) info.centerX < bestGapPos else info.centerX >= bestGapPos
            }.map { it.line }
    }

    private fun filterFacingPage(
        blocks: List<OcrBlock>,
        imageWidth: Int,
    ): List<OcrBlock> {
        if (blocks.size <= 1) return blocks

        // Collect block centers with their text lengths
        data class BlockInfo(
            val block: OcrBlock,
            val centerX: Int,
            val leftX: Int,
            val rightX: Int,
            val textLen: Int,
        )
        val infos =
            blocks.map { block ->
                val box = block.boundingBox
                BlockInfo(block, box.centerX(), box.left, box.right, block.text.length)
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
            if (info.centerX < bestGapPos) {
                leftLen += info.textLen
            } else {
                rightLen += info.textLen
            }
        }

        val keepLeft = leftLen > rightLen

        // Rescue minor-side blocks whose left edge overlaps with the major column.
        // Short body text lines (e.g. paragraph endings) start at the same left margin
        // as full-width lines but have center X shifted toward the spine.
        val majorBlocks =
            infos.filter { info ->
                if (keepLeft) info.centerX < bestGapPos else info.centerX >= bestGapPos
            }
        val majorLeftEdges = majorBlocks.map { it.leftX }.sorted()
        val majorMedianLeft =
            if (majorLeftEdges.isNotEmpty()) {
                majorLeftEdges[majorLeftEdges.size / 4]
            } else {
                0
            }
        val leftEdgeTolerance = (imageWidth * 0.05).toInt()

        return infos
            .filter { info ->
                val onMajorSide = if (keepLeft) info.centerX < bestGapPos else info.centerX >= bestGapPos
                if (onMajorSide) {
                    true
                } else {
                    // Keep minor-side blocks whose left edge aligns with the major column
                    Math.abs(info.leftX - majorMedianLeft) < leftEdgeTolerance
                }
            }.map { it.block }
    }

    private fun denoiseImage(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        OpenCvCompat.bitmapToMat(bitmap, mat)
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
        OpenCvCompat.matToBitmap(rgba, result)
        mat.release()
        sharpened.release()
        rgba.release()
        return result
    }

    private data class DeskewResult(
        val bitmap: Bitmap,
        val angleDegrees: Double,
    )

    private fun deskewImage(bitmap: Bitmap): DeskewResult {
        val mat = Mat()
        OpenCvCompat.bitmapToMat(bitmap, mat)

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
        Imgproc.warpAffine(
            mat,
            rotated,
            rotMat,
            Size(mat.cols().toDouble(), mat.rows().toDouble()),
            Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE,
        )

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        OpenCvCompat.matToBitmap(rotated, result)

        mat.release()
        rotMat.release()
        rotated.release()

        return DeskewResult(result, medianAngle)
    }

    /**
     * Map a bounding box from deskewed image coordinates back to original image coordinates
     * by applying the inverse rotation around the image center.
     */
    private fun undeskewBounds(
        bounds: BoundingBox,
        angleDegrees: Double,
        imageWidth: Int,
        imageHeight: Int,
    ): BoundingBox {
        if (angleDegrees == 0.0) return bounds
        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0
        val rad = Math.toRadians(-angleDegrees) // reverse: rotate back by negating the deskew angle
        val cos = Math.cos(rad)
        val sin = Math.sin(rad)

        // Rotate the four corners of the bounding box
        val corners =
            listOf(
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

    private fun applyExifRotation(
        path: String,
        bitmap: Bitmap,
    ): Bitmap {
        val exif = ExifInterface(path)
        val orientation =
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        val degrees =
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> return bitmap
            }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Spell-correct text using Lucene Hunspell with full affix expansion.
     * Only corrects words that are NOT in the dictionary and have exactly
     * one edit-distance-1 candidate that IS in the dictionary.
     */
    private fun isValidInAnyDict(word: String): Boolean {
        val lower = word.lowercase()
        return hunspellCheckers.values.any { it.spell(word) || it.spell(lower) }
    }

    private fun spellCorrectText(
        text: String,
        checker: Hunspell,
    ): String {
        val result = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i].isLetter()) {
                val start = i
                while (i < text.length && text[i].isLetter()) i++
                val word = text.substring(start, i)
                // Skip spell correction for words that form part of a contraction (e.g., "doesn" before "'t")
                val beforeApostrophe = i < text.length - 1 && text[i] == '\'' && text[i + 1].isLetter()
                val corrected = if (beforeApostrophe) null else tryCorrectWord(word, checker)
                result.append(corrected ?: word)
            } else {
                result.append(text[i])
                i++
            }
        }
        return result.toString()
    }

    private fun tryCorrectWord(
        word: String,
        checker: Hunspell,
    ): String? {
        if (word.length < 5) return null

        // Fix OCR internal case errors (e.g., suPporter -> supporter)
        // If the word has unexpected uppercase in the middle, and the original is NOT valid
        // but the case-fixed version IS valid, fix the casing
        if (word.length >= 2 && !word.all { it.isUpperCase() } && word.drop(1).any { it.isUpperCase() }) {
            val asIsValid = hunspellCheckers.values.any { it.spell(word) }
            if (!asIsValid) {
                val fixed = word[0] + word.substring(1).lowercase()
                if (hunspellCheckers.values.any { it.spell(fixed) || it.spell(fixed.lowercase()) }) {
                    return fixed
                }
            }
        }

        // Skip if already valid in ANY language dictionary
        if (isValidInAnyDict(word)) return null

        val lower = word.lowercase()
        val candidates = mutableSetOf<String>()

        // Helper: check candidate in both lowercase and capitalized forms
        fun spellValid(c: String): Boolean = checker.spell(c) || checker.spell(c.replaceFirstChar { it.uppercase() })

        // Deletions (remove one character)
        for (j in lower.indices) {
            val c = lower.removeRange(j, j + 1)
            if (c.length >= 4 && spellValid(c)) candidates.add(c)
        }

        // Build substitution alphabet: a-z + common accented chars
        val extraChars = "àáâãäåæçèéêëìîïñòóôõöùúûüýÿœß"
        val alphabet = ('a'..'z').toList() + extraChars.toList()

        // Substitutions
        for (j in lower.indices) {
            for (ch in alphabet) {
                if (ch != lower[j]) {
                    val c = lower.substring(0, j) + ch + lower.substring(j + 1)
                    if (spellValid(c)) candidates.add(c)
                }
            }
        }

        // Insertions (add one character)
        for (j in 0..lower.length) {
            for (ch in alphabet) {
                val c = lower.substring(0, j) + ch + lower.substring(j)
                if (spellValid(c)) candidates.add(c)
            }
        }

        // Only accept if exactly one correction found (unambiguous)
        if (candidates.size == 1) {
            return applyCasing(word, candidates.first())
        }
        // Tiebreaker: if multiple candidates, keep only those sharing the same first letter
        if (candidates.size > 1) {
            val firstChar = lower[0]
            val sameFirst = candidates.filter { it.isNotEmpty() && it[0] == firstChar }
            if (sameFirst.size == 1) {
                return applyCasing(word, sameFirst.first())
            }
        }
        return null
    }

    private fun applyCasing(
        original: String,
        corrected: String,
    ): String {
        if (original.all { it.isUpperCase() }) return corrected.uppercase()
        if (original.firstOrNull()?.isUpperCase() == true) {
            return corrected.replaceFirstChar { it.uppercase() }
        }
        return corrected
    }

    override suspend fun cleanup() {
        engine.close()
        langDetector?.close()
    }
}
