import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";
import { createReactFlatConfig } from "../../tools/eslint/react-flat-config.mjs";

export default createReactFlatConfig({
  js,
  globals,
  tseslint,
  reactHooks,
  reactRefresh,
});
