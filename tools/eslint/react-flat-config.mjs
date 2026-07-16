export function createReactFlatConfig({
  js,
  globals,
  tseslint,
  reactHooks,
  reactRefresh,
}) {
  return tseslint.config(
    {
      ignores: ["coverage/**", "dist/**"],
    },
    js.configs.recommended,
    ...tseslint.configs.recommended,
    {
      files: ["**/*.{js,mjs,ts,tsx}"],
      languageOptions: {
        ecmaVersion: "latest",
        globals: {
          ...globals.browser,
          ...globals.node,
        },
      },
    },
    {
      files: ["**/*.{ts,tsx}"],
      plugins: {
        "react-hooks": reactHooks,
      },
      rules: {
        "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
        "react-hooks/exhaustive-deps": "warn",
        "react-hooks/rules-of-hooks": "error",
      },
    },
    {
      files: ["**/*.tsx"],
      plugins: {
        "react-refresh": reactRefresh,
      },
      rules: {
        "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
      },
    },
  );
}
