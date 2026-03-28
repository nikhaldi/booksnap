# BookSnap

BookSnap reliably extracts text from a photo of a book page taken on a mobile device. It handles the surprising number of problems that come up in the process:

- Detecting page boundaries
- Correcting perspective, skew, rotation
- Text recognition with native platform capabilities
- Assembling paragraphs
- Spell checking and fixing common text recognition problems
- Detecting a page number

The current version of BookSnap has been trained on and optimised for book pages in English, French, German and Italian. It may work for other languages, just not as well.

BookSnap is currently bundled as a React Native (Expo) library, with the core logic implemented natively, separately for Android and iOS. All processing happens on-device, the code makes no use of the network or any backend services.

## Usage (React Native)

```ts
import { scanPage } from "react-native-booksnap";

const result = await scanPage(photoUri);

console.log(result.text);         // extracted text with paragraph breaks
console.log(result.pageNumber);   // page number if detected
```

### Result

This is the type returned from `scanPage`:

```ts
interface ScanResult {
  /** Clean extracted text with paragraph breaks. */
  text: string;
  /** Bounding box of the body text region in the input image (pixels). */
  textBounds: BoundingBox;
  /** Page number if detected (e.g. from top/bottom margin). */
  pageNumber?: number;
  /** Bounding box of the page number in the original image (pixels). */
  pageNumberBounds?: BoundingBox;
}

interface BoundingBox {
  x: number;
  y: number;
  width: number;
  height: number;
}
```

### Options

The second argument to `scanPage` is an options dictionary, as in:

```ts
const result = await scanPage(photoUri, { spellCheck: false });
```

Supported options:

| Option       | Type      | Default | Description                    |
| ------------ | --------- | ------- | ------------------------------ |
| `spellCheck` | `boolean` | `true`  | Enable/disable spell correction. You may want to disable this to conserve processing time and memory (as dictionaries may need to be loaded into memory). |

## Development

### Autoresearch Lab

The native text recognition pipelines are developed using [Autoresearch Lab](https://github.com/nikhaldi/autoresearch-lab). Instead of hand-tuning preprocessing, OCR parameters and postprocessing, AI agents iterate on the pipeline code autonomously, evaluating their progress against a ground truth dataset.

Each platform has its own lab in [`android/lab/`](android/lab/) and [`ios/lab/`](ios/lab/). A lab consists of:

- **AGENT.md** — the agent's instructions: scoring formula, constraints, available libraries, and research directions
- **lab.toml** — configuration pointing at the pipeline source, backend, and sandbox
- **A backend** — connects the lab to a platform-specific build and evaluation environment (Android emulator or iOS simulator via a daemon)
- **results.tsv** - a record of kept and discarded experiments so far

### Operating a lab

Prerequisites (yes, there are many):

- Install [Node.js](https://nodejs.org/en/download) and [pnpm](https://pnpm.io/installation) (package management for the JS part)
- Install [uv](https://docs.astral.sh/uv/getting-started/installation/) (package management for the Python-based labs)
- Install [Docker](https://docs.docker.com/desktop/) (sandboxing the research loop)
- Install an Android SDK (compileSdk 34) and JDK 17 (for Android development)
- Install Xcode (for iOS development - only possible on OS X)

```sh
# One-time setup of the development environment
pnpm run setup

cd android/lab/  # or `cd ios/lab/`

# TODO further instructions here
```

### Tests

```sh
# TypeScript unit tests
pnpm test

# Android unit tests (Robolectric)
pnpm run test:android

# iOS unit tests (OS X only)
pnpm run test:ios
```

### Datasets

TODO describe datasets

### Lab branches

Aside from `main` this repo has 3 significant branches:

- `lab-base`: Starting point for new lab runs. Contains the pipeline scaffolding, test harness, and evaluation infrastructure, but with minimal pipeline logic. Create a branch off of this if you want to run a new research loop from scratch (e.g., with a new dataset or new agent instructions or just because a new AI model came out that may do better (!)).
- `lab-android`: The Android pipeline as developed by the research agent. Each commit is a kept experiment with its score improvements noted in the commit message. There may be human-authored commits interleaved where human intervention was necessary during development.
- `lab-ios`: Same as above for the iOS pipeline.

Guidelines for working with branches:

- **Only run the research loop on a lab branch** (never on `main`) as the loop will create a commit for each successful experiment.
- Before running a research loop on a lab branch it is a good idea to pull from `main` and merge `main` into the lab branch to ensure it is up-to-date. (The `lab-base` branch will be kept in sync by maintainers as much as possible.)
- To publish changes developed on a lab branch, create a PR that squashes & merges all changes from the lab branch onto `main`. The commit history on lab branches should be preserved as a record of what actually happened in research.

## License

BookSnap is distributed under an [MIT license](LICENSE.txt).
