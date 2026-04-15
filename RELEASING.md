# Releasing

To publish a new version of the library:

1. **Create a release branch and bump the version:**
   ```sh
   git checkout -b release/0.3.0
   npm version patch --no-git-tag-version  # or minor, or major
   git add package.json
   git commit -m "Bump version to 0.3.0"
   ```

2. **Run integration tests and pre-publish checks:**
   ```sh
   pnpm run test:integration
   pnpm run publish:check
   ```

3. **Push and create a PR to main:**
   ```sh
   git push -u origin release/0.3.0
   ```

4. **After the PR is merged**, tag the merge commit and create a GitHub release:
   ```sh
   git checkout main && git pull
   git tag v0.3.0
   git push --tags
   ```
   Creating the release triggers CI, which publishes to npm via [trusted publishing](https://docs.npmjs.com/generating-provenance-statements#publishing-packages-with-provenance-via-github-actions).

5. **Deploy diff reports** for the new version:
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
