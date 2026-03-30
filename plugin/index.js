const { withGradleProperties } = require("expo/config-plugins");

/**
 * Expo config plugin for react-native-booksnap.
 *
 * Configures which Hunspell dictionary languages are downloaded at Android
 * build time for spell correction. Languages default to ["en"] if not specified.
 *
 * Usage in app.json:
 *   ["react-native-booksnap", { "languages": ["en", "en-GB", "fr", "de", "it"] }]
 */
function withBookSnap(config, props) {
  const languages = (props && props.languages) || ["en"];
  const langsValue = languages.join(",");

  return withGradleProperties(config, (config) => {
    config.modResults = config.modResults.filter(
      (item) => !(item.type === "property" && item.key === "hunspell.langs"),
    );

    config.modResults.push({
      type: "property",
      key: "hunspell.langs",
      value: langsValue,
    });

    return config;
  });
}

module.exports = withBookSnap;
