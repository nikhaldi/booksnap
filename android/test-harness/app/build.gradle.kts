plugins {
    id("com.android.application")
}

val hunspellLangsStr = findProperty("hunspell.langs") as? String ?: "en,en-GB,fr,de,it"
val hunspellLangs = hunspellLangsStr.split(",").map { it.trim() }

android {
    namespace = "dev.booksnap.harness"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.booksnap.harness"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "HUNSPELL_LANGS", "\"$hunspellLangsStr\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.testLogging {
                    events("passed", "failed", "skipped")
                    showStandardStreams = true
                }
            }
        }
    }

    // Include shared types from the library and the agent's pipeline source directory.
    // Shared types (PageResult, BoundingBox) live in the library's src/shared/java/.
    // The agent's pipeline implementation is copied by the daemon into src/pipeline/java/.
    sourceSets {
        getByName("main") {
            kotlin.directories.add("../../src/shared/java")
            kotlin.directories.add("src/pipeline/java")
        }
    }
}

dependencies {
    // Core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // --- Pre-installed libraries available to the pipeline ---
    // The agent can use any of these without modifying dependencies.gradle.

    // OCR engines
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // Image processing
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.opencv:opencv:4.9.0")
    implementation("jp.co.cyberagent.android:gpuimage:2.1.0")

    // Alternative OCR engine
    implementation("com.rmtheis:tess-two:9.1.0")

    // Language detection
    implementation("com.google.mlkit:language-id:17.0.6")

    // Spell checking — Lucene's pure-Java Hunspell implementation
    // Loads standard .dic/.aff files with full affix expansion
    // Word lists in assets/hunspell/ for en, fr, de, it, el
    implementation("org.apache.lucene:lucene-analyzers-common:8.11.4")

    // JVM unit tests (Robolectric)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.openpnp:opencv:4.9.0-0")

    // Instrumented tests (emulator)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.google.code.gson:gson:2.14.0")
}

// Sync pipeline source from the library into the harness before compilation.
// This ensures tests always run against the current library pipeline, not a stale copy.
val syncPipeline = tasks.register<Sync>("syncPipelineSource") {
    from("../../src/main/java/dev/booksnap/pipeline/pipeline") {
        include("*.kt")
    }
    into("src/pipeline/java/pipeline")
}

tasks.matching { it.name.contains("compile", ignoreCase = true) && it.name.contains("Kotlin") }.configureEach {
    dependsOn(syncPipeline)
}

// Download Hunspell dictionaries for the configured languages at build time.
extra["hunspellLangsList"] = hunspellLangs
extra["hunspellOutDir"] = file("src/main/assets/hunspell")
apply(from = "../../../android/hunspell-download.gradle")

tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }.configureEach {
    dependsOn("downloadHunspellDictionaries")
}

// Apply pipeline-declared dependencies if present
val pipelineDeps = file("src/pipeline/java/dependencies.gradle")
if (pipelineDeps.exists()) {
    apply(from = pipelineDeps)
}
