package dev.booksnap.module

import dev.booksnap.BuildConfig
import dev.booksnap.pipeline.BookSnapPipeline
import dev.booksnap.pipeline.BoundingBox
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.runBlocking

/**
 * Expo module wrapper — thin adapter around BookSnapPipeline.
 *
 * Pure Android consumers use BookSnapPipeline directly;
 * React Native consumers go through this module.
 */
class BookSnapModule : Module() {
    private val pipeline = BookSnapPipeline()
    private var initialized = false

    override fun definition() =
        ModuleDefinition {
            Name("BookSnap")

            AsyncFunction("scanPage") { inputPath: String, options: Map<String, Any> ->
                val path =
                    if (inputPath.startsWith("file://")) {
                        android.net.Uri
                            .parse(inputPath)
                            .path ?: inputPath
                    } else {
                        inputPath
                    }

                val mergedOptions = mutableMapOf<String, Any>()
                mergedOptions["hunspellLangs"] = BuildConfig.HUNSPELL_LANGS
                mergedOptions.putAll(options)

                if (!initialized) {
                    runBlocking {
                        pipeline.initialize(appContext.reactContext!!, mergedOptions)
                    }
                    initialized = true
                }
                val result =
                    runBlocking {
                        pipeline.processImage(path)
                    }

                val map =
                    mutableMapOf<String, Any?>(
                        "text" to result.text,
                        "textBounds" to boundsToMap(result.textBounds),
                        "pageNumber" to result.pageNumber,
                        "pageNumberBounds" to result.pageNumberBounds?.let { boundsToMap(it) },
                    )
                map
            }
        }

    private fun boundsToMap(b: BoundingBox): Map<String, Int> =
        mapOf(
            "x" to b.x,
            "y" to b.y,
            "width" to b.width,
            "height" to b.height,
        )
}
