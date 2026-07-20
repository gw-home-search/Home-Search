#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ai_root="$(cd "${script_dir}/.." && pwd)"
html_dir="${ai_root}/build/reference-docs/html"
test -f "${html_dir}/index.html" || "${script_dir}/build-reference-docs.sh"
cd "$html_dir"
exec python3 -m http.server "${HOME_AI_REFERENCE_DOCS_PORT:-8090}" --bind 127.0.0.1
