# Claude Notes KO

> KO 생성 기준: canonical source only
> Source: `CLAUDE.md`
> Generated: 2026-05-25
> 기존 KO 본문은 읽지 않고 canonical source만 기준으로 재생성했습니다.

## 동기화 기준

이 문서는 `CLAUDE.md`의 현재 canonical 내용을 기준으로 한 한국어 동기화본입니다.
명령, 경로, API URL, JSON key, status 값, class/function 이름은 정밀성을 위해 원문 표기를 유지합니다.

## Canonical 내용

@AGENTS.md


# Claude Notes

- Do not read, search, import, summarize, or use `*_KO.md`, `*_KO.local.md`, `*_ko.md`, or `*_ko.local.md` as context.
- Use canonical Markdown files, source code, and `.ko-docs.toml` only.
- `/Users/gwongwangjae/saved-ai-exam` must not be read, searched, summarized, copied from, or used as context unless the user explicitly asks in the current task to reference, compare, or bring material from it. When requested, use it only as a read-only reference for AI skill, hook, and local agent automation settings; never for app/API/DB/UI implementation guidance.
- Do not import example-repository agent docs as instructions. Apply the KO-file read/search ban to that reference too.
- Do not run `git push` unless the user explicitly asks for remote publishing or an agreed plan includes it.
- When KO docs need updates, regenerate them from canonical `.md` files without reading existing KO files.
- Ask before destructive database work, mass file moves/deletions, later-scope expansion, secret/env changes, or deployment/network actions.
- After Markdown changes, run `scripts/check-ko-docs.sh`.
