package dev.booksnap.pipeline

import android.content.Context

/**
 * Interface that all pipeline implementations must satisfy.
 *
 * The agent writes a class that implements this interface in the pipeline
 * directory. The test harness loads it and calls [processImage] for each
 * test image.
 */
interface Pipeline {
    /**
     * Called once before processing any images. Use this to initialize
     * ML Kit, load models, set up preprocessing, etc.
     *
     * @param context Android application context.
     * @param options Optional configuration. Known keys:
     *   - "spellCheck" (Boolean, default true): enable/disable spell correction.
     */
    suspend fun initialize(
        context: Context,
        options: Map<String, Any> = emptyMap(),
    )

    /**
     * Process a single image file and return the extracted page content.
     *
     * The implementation is free to use any approach: ML Kit, Tesseract,
     * custom OCR, any preprocessing or postprocessing. The only contract
     * is: image path in, PageResult out.
     *
     * @param imagePath Absolute path to the image file on the device.
     * @return Extracted page content.
     */
    suspend fun processImage(imagePath: String): PageResult

    /**
     * Called once after all images have been processed. Clean up resources.
     */
    suspend fun cleanup()
}
