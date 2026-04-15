const { withGradleProperties } = require("expo/config-plugins");

const MIN_SDK_VERSION = 26;

/**
 * Expo config plugin for react-native-booksnap.
 *
 * Configures which Hunspell dictionary languages are downloaded at Android
 * build time for spell correction. Languages default to ["en"] if not specified.
 *
 * Also ensures minSdkVersion is at least 26 (required by the Lucene Hunspell
 * spell-checking library).
 *
 * Usage in app.json:
 *   ["react-native-booksnap", { "languages": ["en", "en-GB", "fr", "de", "it"] }]
 */
function withBookSnap(config, props) {
  const languages = (props && props.languages) || ["en"];
  const langsValue = languages.join(",");

  return withGradleProperties(config, (config) => {
    // Set hunspell.langs
    config.modResults = config.modResults.filter(
      (item) => !(item.type === "property" && item.key === "hunspell.langs"),
    );
    config.modResults.push({
      type: "property",
      key: "hunspell.langs",
      value: langsValue,
    });

    // Ensure minSdkVersion >= 26 (required by Lucene Hunspell)
    const minSdkEntry = config.modResults.find(
      (item) => item.type === "property" && item.key === "android.minSdkVersion",
    );
    if (!minSdkEntry || parseInt(minSdkEntry.value, 10) < MIN_SDK_VERSION) {
      config.modResults = config.modResults.filter(
        (item) => !(item.type === "property" && item.key === "android.minSdkVersion"),
      );
      config.modResults.push({
        type: "property",
        key: "android.minSdkVersion",
        value: String(MIN_SDK_VERSION),
      });
    }

    return config;
  });
}

module.exports = withBookSnap;
