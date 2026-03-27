package dev.booksnap.pipeline

import android.graphics.Bitmap
import org.opencv.core.Mat

/**
 * Compatibility layer for OpenCV initialization and Bitmap↔Mat conversion.
 *
 * On Android, uses `org.opencv.android.OpenCVLoader` and `org.opencv.android.Utils`.
 * In JVM tests (Robolectric), falls back to `nu.pattern.OpenCV` for initialization
 * and manual pixel copying for Bitmap conversion.
 */
object OpenCvCompat {

    private var initialized = false

    /** Initialize OpenCV native libs. Safe to call from both Android and JVM tests. */
    fun init() {
        if (initialized) return
        // Try Android-specific loader first.
        // Suppress stderr during the attempt — on JVM tests, OpenCVLoader.initLocal()
        // prints an UnsatisfiedLinkError stack trace before we can catch it.
        val originalErr = System.err
        val androidLoaded = try {
            System.setErr(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
            val clazz = Class.forName("org.opencv.android.OpenCVLoader")
            val method = clazz.getMethod("initLocal")
            method.invoke(null)
            true
        } catch (e: Exception) {
            false
        } finally {
            System.setErr(originalErr)
        }
        if (androidLoaded) {
            initialized = true
            return
        }
        // Fall back to desktop loader (openpnp jar for JVM tests)
        try {
            val clazz = Class.forName("nu.pattern.OpenCV")
            val method = clazz.getMethod("loadLocally")
            method.invoke(null)
            initialized = true
        } catch (e: Exception) {
            System.err.println("OpenCV init failed: ${e.message}")
        }
    }

    /** Convert a Bitmap to an OpenCV Mat. */
    fun bitmapToMat(bitmap: Bitmap, mat: Mat) {
        try {
            val clazz = Class.forName("org.opencv.android.Utils")
            val method = clazz.getMethod("bitmapToMat", Bitmap::class.java, Mat::class.java)
            method.invoke(null, bitmap, mat)
        } catch (e: Exception) {
            // JVM test fallback: manual pixel copy
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            mat.create(height, width, org.opencv.core.CvType.CV_8UC4)
            val data = ByteArray(width * height * 4)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                data[i * 4] = (pixel shr 16 and 0xFF).toByte() // R
                data[i * 4 + 1] = (pixel shr 8 and 0xFF).toByte() // G
                data[i * 4 + 2] = (pixel and 0xFF).toByte() // B
                data[i * 4 + 3] = (pixel shr 24 and 0xFF).toByte() // A
            }
            mat.put(0, 0, data)
        }
    }

    /** Convert an OpenCV Mat to a Bitmap. */
    fun matToBitmap(mat: Mat, bitmap: Bitmap) {
        try {
            val clazz = Class.forName("org.opencv.android.Utils")
            val method = clazz.getMethod("matToBitmap", Mat::class.java, Bitmap::class.java)
            method.invoke(null, mat, bitmap)
        } catch (e: Exception) {
            // JVM test fallback: manual pixel copy
            val width = mat.cols()
            val height = mat.rows()
            val data = ByteArray(width * height * mat.channels())
            mat.get(0, 0, data)
            val pixels = IntArray(width * height)
            val channels = mat.channels()
            for (i in pixels.indices) {
                if (channels == 4) {
                    val r = data[i * 4].toInt() and 0xFF
                    val g = data[i * 4 + 1].toInt() and 0xFF
                    val b = data[i * 4 + 2].toInt() and 0xFF
                    val a = data[i * 4 + 3].toInt() and 0xFF
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                } else if (channels == 3) {
                    val r = data[i * 3].toInt() and 0xFF
                    val g = data[i * 3 + 1].toInt() and 0xFF
                    val b = data[i * 3 + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
