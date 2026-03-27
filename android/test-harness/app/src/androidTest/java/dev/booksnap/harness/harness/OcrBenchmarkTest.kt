package dev.booksnap.harness

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.booksnap.pipeline.Pipeline
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented test that runs a pipeline on a batch of images and writes
 * results as JSON. Driven by the booksnap-lab harness.
 *
 * The pipeline implementation is provided by the agent — it must be a class
 * called `dev.booksnap.pipeline.BookSnapPipeline` that implements [Pipeline].
 *
 * Input files are pushed to /data/local/tmp/booksnap/ (readable by all apps).
 * Output is written to the app's internal files dir and pulled via `adb run-as`:
 *   Input:  /data/local/tmp/booksnap/manifest.json
 *   Output: /data/data/dev.booksnap.harness/files/results.json
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkTest {

    data class ManifestEntry(val id: String, val path: String)
    data class Manifest(val images: List<ManifestEntry>)

    data class BoundsResult(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    data class ImageResult(
        val image_id: String,
        val extracted_text: String,
        val latency_ms: Long,
        val platform: String = "android",
        val error: String? = null,
        val page_number: Int? = null,
        val text_bounds: BoundsResult? = null,
        val page_number_bounds: BoundsResult? = null,
    )

    data class Results(val results: List<ImageResult>)

    private val gson = Gson()
    private val baseDir = File("/data/local/tmp/booksnap")

    @Test
    fun runBenchmark() = runBlocking {
        val manifestFile = File(baseDir, "manifest.json")
        require(manifestFile.exists()) {
            "Manifest not found at ${manifestFile.absolutePath}. " +
            "Push it via: adb push manifest.json /data/local/tmp/booksnap/"
        }

        val manifest = gson.fromJson(
            manifestFile.readText(), Manifest::class.java
        )

        val pipeline = loadPipeline()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val options = mutableMapOf<String, Any>()
        options["hunspellLangs"] = BuildConfig.HUNSPELL_LANGS
        args.getString("spellCheck")?.let {
            options["spellCheck"] = it.toBooleanStrictOrNull() ?: true
        }
        pipeline.initialize(context, options)

        val results = manifest.images.map { entry ->
            val imageFile = File(baseDir, entry.path)
            if (!imageFile.exists()) {
                return@map ImageResult(
                    image_id = entry.id,
                    extracted_text = "",
                    latency_ms = 0,
                    error = "Image not found: ${imageFile.absolutePath}"
                )
            }

            try {
                val startTime = SystemClock.elapsedRealtime()
                val result = pipeline.processImage(imageFile.absolutePath)
                val elapsed = SystemClock.elapsedRealtime() - startTime

                val tb = result.textBounds
                val pnb = result.pageNumberBounds

                ImageResult(
                    image_id = entry.id,
                    extracted_text = result.text,
                    latency_ms = elapsed,
                    page_number = result.pageNumber,
                    text_bounds = if (tb.width > 0) BoundsResult(
                        tb.x, tb.y, tb.width, tb.height
                    ) else null,
                    page_number_bounds = if (pnb != null) BoundsResult(
                        pnb.x, pnb.y, pnb.width, pnb.height
                    ) else null,
                )
            } catch (e: Exception) {
                ImageResult(
                    image_id = entry.id,
                    extracted_text = "",
                    latency_ms = 0,
                    error = "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        pipeline.cleanup()

        // Write results to app-internal storage (writable by the app)
        val resultsFile = File(context.filesDir, "results.json")
        resultsFile.writeText(gson.toJson(Results(results)))
    }

    private fun loadPipeline(): Pipeline {
        try {
            val clazz = Class.forName("dev.booksnap.pipeline.BookSnapPipeline")
            return clazz.getDeclaredConstructor().newInstance() as Pipeline
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "Pipeline class not found: dev.booksnap.pipeline.BookSnapPipeline. " +
                "The agent must provide a class implementing Pipeline " +
                "in the pipeline directory.",
                e
            )
        }
    }
}
