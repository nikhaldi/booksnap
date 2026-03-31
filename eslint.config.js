import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import prettier from "eslint-config-prettier";

export default [
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  prettier,
  { ignores: ["build/", "node_modules/", "plugin/", "app.plugin.cjs"] },
];
