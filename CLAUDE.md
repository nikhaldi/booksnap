# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

BookSnap is a React Native (Expo) library that extracts text from photos of book pages. It ships as an npm package (`react-native-booksnap`) with native implementations on both platforms: Kotlin/Android using ML Kit, Swift/iOS using Apple Vision. All processing is on-device.

## Commands

### Setup
```sh
pnpm install          # install JS dependencies (dictionaries download automatically at build time)
```

### Tests
```sh
pnpm test             # TypeScript unit tests (vitest)
pnpm run test:android # Android unit tests (Robolectric, requires JDK 17 + Android SDK)
pnpm run test:ios     # iOS unit tests (xcodebuild on simulator, macOS only)
```

### Linting & Formatting
```sh
pnpm run lint              # ESLint on src/
pnpm run format:check      # Prettier check on src/
pnpm run format            # Prettier fix on src/
pnpm run lint:android      # ktlint via standalone binary (android/scripts/ktlint.sh)
pnpm run format:android    # ktlint fix
pnpm run lint:ios          # SwiftLint via standalone binary (ios/scripts/swiftlint.sh)
pnpm run format:ios        # SwiftLint fix
```

### Other
```sh
pnpm run simulator:boot    # boot an iOS simulator if none is running
pnpm run publish:check     # pre-publish sanity checks (file contents, size, npm pkg fix)
pnpm run reports:deploy    # generate and deploy OCR diff reports to gh-pages
pnpm run dataset:pack <name>  # tar.gz a dataset directory (e.g. booksnap-base)
```

## Architecture

### Three-layer structure

1. **TypeScript layer** (`src/`) — thin bridge that calls the native module via `expo-modules-core`. Exports `scanPage()` and types. This is what consumers import.

2. **Native modules** (`android/src/main/.../BookSnapModule.kt`, `ios/BookSnapModule.swift`) — Expo module wrappers that call the pipeline and marshal results back to JS.

3. **Pipelines** (`android/src/main/.../pipeline/`, `ios/pipeline/`) — the actual OCR logic. These are developed autonomously by AI agents via the Autoresearch Lab system, not hand-written. Each platform has its own pipeline implementation.

### Autoresearch Lab

The `android/lab/` and `ios/lab/` directories contain configuration for running AI research agents that iterate on the pipeline code. The agent writes Kotlin/Swift, builds the test harness, runs it on an emulator/simulator, evaluates against ground truth, and commits improvements. Key files:
- `AGENT.md` — agent instructions, scoring formula, constraints
- `lab.toml` — lab configuration
- `results.tsv` — history of kept/discarded experiments

### Test harnesses

Each platform has a test harness (`android/test-harness/`, `ios/test-harness/`) that serves dual purpose:
- **Unit tests** run in CI (Robolectric on Android, xcodebuild on iOS simulator)
- **Evaluation harness** used by the lab's research loop to score pipeline changes against datasets

The iOS test harness uses XcodeGen (`project.yml` generates `.xcodeproj`). The generated project is committed so CI doesn't need xcodegen. Regenerate with `xcodegen generate` after changing `project.yml`.

### Hunspell dictionaries (Android only)

Spell-check dictionaries are downloaded from github.com/wooorm/dictionaries at Gradle build time via `android/hunspell-download.gradle`. The language list is configured by consumers via the Expo config plugin in `app.json`, which writes `hunspell.langs` to `gradle.properties`. Default: English only. The iOS pipeline uses `UITextChecker` instead (system dictionaries, no downloads needed).

### Version management

`package.json` is the single source of truth for the version. `android/build.gradle` reads it via `JsonSlurper`, `ios/BookSnap.podspec` reads it via Ruby JSON. Python packages in `shared/python/` and labs use a static `0.0.0dev` placeholder — they're never published.

### Branch structure

- `main` — stable, publishable code
- `lab-base` — starting point for new research loops
- `lab-android`, `lab-ios` — pipeline code as developed by research agents (one commit per experiment)

## Conventions

- The `plugin/` directory is CommonJS (Expo config plugins require it) — excluded from ESLint
- ktlint and SwiftLint binaries are auto-downloaded by wrapper scripts on first use, not installed globally
- The iOS pipeline copy at `ios/test-harness/BookSnapHarness/Pipeline/` is gitignored — it's copied from `ios/pipeline/` by the test runner script
- SwiftLint rules are relaxed for structural limits (file length, complexity) because pipeline code is agent-generated and grows organically
