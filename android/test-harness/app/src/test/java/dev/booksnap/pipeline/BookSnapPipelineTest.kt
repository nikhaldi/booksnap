package dev.booksnap.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * JVM unit tests for BookSnapPipeline using Robolectric + mock OCR engine.
 *
 * These tests verify the pipeline contract and core logic without needing
 * an Android emulator or real ML Kit. OpenCV runs via the openpnp desktop jar.
 */
@RunWith(RobolectricTestRunner::class)
class BookSnapPipelineTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initOpenCv() {
            // Must load before any OpenCV class is referenced
            try {
                nu.pattern.OpenCV.loadLocally()
            } catch (e: Exception) {
                System.err.println("OpenCV desktop load failed: ${e.message}")
            }
        }
    }

    private var activePipeline: BookSnapPipeline? = null

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking { activePipeline?.cleanup() }
        activePipeline = null
    }

    // -- Contract tests --

    @Test
    fun `processImage returns empty text for missing file`() =
        runTest {
            val pipeline = BookSnapPipeline(ocrEngine = emptyMockEngine(), languageDetector = stubLangDetector)
            pipeline.initialize(RuntimeEnvironment.getApplication())

            try {
                val result = pipeline.processImage("/nonexistent/path.jpg")
                assertEquals("", result.text)
            } catch (e: java.io.FileNotFoundException) {
                // Expected — ExifInterface throws for missing files
            }
        }

    @Test
    fun `processImage returns empty text for empty OCR result`() =
        runTest {
            val pipeline = createPipeline(emptyMockEngine())

            val result = pipeline.processImage(testImagePath())
            assertEquals("", result.text)
        }

    @Test
    fun `processImage returns PageResult with text and bounds from OCR`() =
        runTest {
            val engine =
                mockEngineWithBlocks(
                    listOf(
                        ocrBlock("Hello world.", Rect(50, 300, 900, 340)),
                        ocrBlock("Second line.", Rect(50, 360, 900, 400)),
                    ),
                )
            val pipeline = createPipeline(engine)

            val result = pipeline.processImage(testImagePath())
            assertTrue("Expected non-blank text", result.text.isNotBlank())
            assertTrue("Should contain first block text", result.text.contains("Hello world"))
            assertTrue("Should contain second block text", result.text.contains("Second line"))

            // textBounds should be the union of all line bounding boxes
            assertEquals("textBounds.x", 50, result.textBounds.x)
            assertEquals("textBounds.y", 300, result.textBounds.y)
            assertEquals("textBounds.width", 850, result.textBounds.width)
            assertEquals("textBounds.height", 100, result.textBounds.height)
        }

    @Test
    fun `extracts page number and its bounding box`() =
        runTest {
            val pageNumRect = Rect(450, 950, 480, 970)
            val engine =
                mockEngineWithBlocks(
                    listOf(
                        ocrBlock("Some body text on the page.", Rect(50, 300, 900, 340)),
                        ocrBlock("More body text continues here.", Rect(50, 360, 900, 400)),
                        ocrBlock("42", pageNumRect),
                    ),
                )
            val pipeline = createPipeline(engine)

            val result = pipeline.processImage(testImagePath())

            assertEquals("Page number should be 42", 42, result.pageNumber)
            assertNotNull("pageNumberBounds should not be null", result.pageNumberBounds)
            assertEquals("pageNumberBounds.x", pageNumRect.left, result.pageNumberBounds!!.x)
            assertEquals("pageNumberBounds.y", pageNumRect.top, result.pageNumberBounds!!.y)
            assertEquals("pageNumberBounds.width", pageNumRect.width(), result.pageNumberBounds!!.width)
            assertEquals("pageNumberBounds.height", pageNumRect.height(), result.pageNumberBounds!!.height)

            // Page number text should not appear in the body text
            assertFalse("Body text should not contain page number", result.text.contains("42"))
        }

    @Test
    fun `all lines filtered returns zero text bounds`() =
        runTest {
            // All lines are short, all-caps, in the top margin — they should all be
            // filtered as running headers, leaving empty text and a zero bounding box.
            // This also exercises the undeskewBounds guard on zero bounds.
            val engine =
                mockEngineWithBlocks(
                    listOf(
                        ocrBlock("CHAPTER TITLE", Rect(100, 10, 400, 40)),
                        ocrBlock("AUTHOR NAME 42", Rect(100, 50, 400, 80)),
                    ),
                )
            val pipeline = createPipeline(engine)

            val result = pipeline.processImage(testImagePath())
            assertEquals("Text should be empty when all lines are filtered", "", result.text)
            assertEquals("textBounds.x should be 0", 0, result.textBounds.x)
            assertEquals("textBounds.y should be 0", 0, result.textBounds.y)
            assertEquals("textBounds.width should be 0", 0, result.textBounds.width)
            assertEquals("textBounds.height should be 0", 0, result.textBounds.height)
        }

    // -- Spell correction --

    @Test
    fun `spell correction English`() =
        runTest {
            assertSpellCorrection("en", "en", "The houze was very large.", "houze", "house")
        }

    @Test
    fun `spell correction French`() =
        runTest {
            assertSpellCorrection("fr", "fr", "La maizon est très belle.", "maizon", "maison")
        }

    @Test
    fun `spell correction German`() =
        runTest {
            assertSpellCorrection("de", "de", "Die Geschichze ist lang.", "Geschichze", "Geschichte")
        }

    @Test
    fun `spell correction Italian`() =
        runTest {
            assertSpellCorrection("it", "it", "La famigkia era grande.", "famigkia", "famiglia")
        }

    // -- Helpers --

    private fun testImagePath(): String {
        val dir = RuntimeEnvironment.getApplication().cacheDir
        val file = java.io.File(dir, "test_image.jpg")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        return file.absolutePath
    }

    private val stubLangDetector =
        object : LanguageDetector {
            override suspend fun identifyLanguage(text: String) = "und"
        }

    private suspend fun createPipeline(engine: OcrEngine): BookSnapPipeline {
        val pipeline = BookSnapPipeline(ocrEngine = engine, languageDetector = stubLangDetector)
        pipeline.initialize(RuntimeEnvironment.getApplication())
        activePipeline = pipeline
        return pipeline
    }

    private fun emptyMockEngine() =
        object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap) = emptyList<OcrBlock>()
        }

    private fun mockEngineWithBlocks(blocks: List<OcrBlock>) =
        object : OcrEngine {
            override suspend fun recognize(bitmap: Bitmap) = blocks
        }

    private suspend fun assertSpellCorrection(
        language: String,
        hunspellLangs: String,
        inputText: String,
        misspelled: String,
        corrected: String,
    ) {
        val langDetector =
            object : LanguageDetector {
                override suspend fun identifyLanguage(text: String) = language
            }
        val engine =
            mockEngineWithBlocks(
                listOf(ocrBlock(inputText, Rect(50, 300, 900, 340))),
            )
        val pipeline = BookSnapPipeline(ocrEngine = engine, languageDetector = langDetector)
        pipeline.initialize(
            RuntimeEnvironment.getApplication(),
            mapOf("spellCheck" to true, "hunspellLangs" to hunspellLangs),
        )
        activePipeline = pipeline

        val result = pipeline.processImage(testImagePath())
        assertTrue(
            "[$language] Should correct '$misspelled' to '$corrected': got '${result.text}'",
            result.text.contains(corrected),
        )
        assertFalse(
            "[$language] Should not contain misspelled '$misspelled'",
            result.text.contains(misspelled),
        )
    }

    private fun ocrBlock(
        text: String,
        boundingBox: Rect,
    ): OcrBlock =
        OcrBlock(
            text = text,
            boundingBox = boundingBox,
            lines = listOf(OcrLine(text = text, boundingBox = boundingBox)),
        )
}
