# Releasing

To publish a new version of the library:

1. **Bump the version** — this updates `package.json` and creates a git tag:
   ```sh
   npm version patch  # or minor, or major
   ```

2. **Run pre-publish checks** manually to verify all is ready:
   ```sh
   pnpm run publish:check
   ```

3. **Push the tag:**
   ```sh
   git push && git push --tags
   ```

3. **Create a GitHub release** from the tag. This triggers CI, which publishes to npm via [trusted publishing](https://docs.npmjs.com/generating-provenance-statements#publishing-packages-with-provenance-via-github-actions).

4. **Deploy diff reports** for the new version:
   ```sh
   pnpm run reports:deploy
   ```
   This generates fresh OCR accuracy reports for each lab and pushes them to [GitHub Pages](https://nikhaldi.github.io/booksnap/).

The version in `package.json` is the single source of truth — `android/build.gradle` and `ios/BookSnap.podspec` both read from it automatically.

## Updating a dataset

Datasets are versioned independently from the library and published as GitHub release assets.

1. **Pack the dataset:**
   ```sh
   pnpm run dataset:pack booksnap-base
   ```

2. **Create a new dataset release:**
   ```sh
   gh release create dataset-booksnap-base-0.2.0 booksnap-base.tar.gz \
     --title "Dataset: booksnap-base 0.2.0" \
     --notes "Description of what changed in the dataset"
   ```

3. **Update the download URL** in the README to point to the new release tag.

Use a new version tag for each dataset update rather than overwriting an existing release — this ensures reproducibility for anyone using an older version.
