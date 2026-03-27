package dev.booksnap.pipeline

/**
 * Interface for language detection. Implementations wrap platform-specific APIs.
 * Decouples the pipeline from ML Kit, making it testable on JVM.
 */
interface LanguageDetector {
    /** Identify the language of the given text. Returns ISO 639-1 code or "und". */
    suspend fun identifyLanguage(text: String): String

    /** Release resources. */
    fun close() {}
}
