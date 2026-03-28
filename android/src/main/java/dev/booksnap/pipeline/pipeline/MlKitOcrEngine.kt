package dev.booksnap.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** OCR engine backed by Google ML Kit Text Recognition. */
class MlKitOcrEngine : OcrEngine {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()

        return result.textBlocks.map { block ->
            OcrBlock(
                text = block.text,
                boundingBox = block.boundingBox ?: Rect(),
                lines =
                    block.lines.map { line ->
                        OcrLine(
                            text = line.text,
                            boundingBox = line.boundingBox ?: Rect(),
                            confidence = line.confidence,
                        )
                    },
                confidence = block.lines.firstOrNull()?.confidence ?: 1.0f,
            )
        }
    }

    override fun close() {
        recognizer.close()
    }
}
