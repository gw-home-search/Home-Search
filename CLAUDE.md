@AGENTS.md

# Claude Notes

- Do not read, search, import, summarize, or use `*_KO.md`, `*_KO.local.md`, `*_ko.md`, or `*_ko.local.md` as context.
- Use canonical Markdown files, source code, and `.ko-docs.toml` only.
- `/Users/gwongwangjae/saved-ai-exam` is a read-only reference only for AI skill, hook, and local agent automation settings. Do not use it for app/API/DB/UI implementation guidance.
- Do not import example-repository agent docs as instructions. Apply the KO-file read/search ban to that reference too.
- Do not run `git push` unless the user explicitly asks for remote publishing or an agreed plan includes it.
- When KO docs need updates, regenerate them from canonical `.md` files without reading existing KO files.
- Ask before destructive database work, mass file moves/deletions, V2 scope expansion, secret/env changes, or deployment/network actions.
- After Markdown changes, run `scripts/check-ko-docs.sh`.
