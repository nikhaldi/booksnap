package dev.booksnap.pipeline

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.tasks.await

/** Language detector backed by Google ML Kit Language Identification. */
class MlKitLanguageDetector : LanguageDetector {

    private val identifier: LanguageIdentifier = LanguageIdentification.getClient()

    override suspend fun identifyLanguage(text: String): String {
        return identifier.identifyLanguage(text).await()
    }

    override fun close() {
        identifier.close()
    }
}
