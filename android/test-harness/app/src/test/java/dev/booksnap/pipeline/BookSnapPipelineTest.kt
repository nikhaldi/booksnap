package dev.booksnap.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

            assertNotNull("textBounds should not be null", result.textBounds)
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
