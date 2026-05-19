@AGENTS.md

# Claude Notes

- `*_KO.md`, `*_KO.local.md`, `*_ko.md`, `*_ko.local.md`를 context로 읽거나, search, import, summarize, use하지 않는다.
- canonical Markdown files, source code, `.ko-docs.toml`만 사용한다.
- `/Users/gwongwangjae/saved-ai-exam`은 user가 현재 task에서 reference, compare, material 가져오기를 명시적으로 요청하지 않는 한 읽거나, search, summarize, copy, context로 사용하면 안 된다. 요청된 경우에도 AI skill, hook, local agent automation settings를 위한 read-only reference로만 사용하며, app/API/DB/UI implementation guidance에는 절대 사용하지 않는다.
- example-repository agent docs를 instructions로 import하지 않는다. 해당 reference에도 KO-file read/search ban을 적용한다.
- user가 remote publishing을 명시적으로 요청하거나 합의된 plan에 포함되지 않는 한 `git push`를 실행하지 않는다.
- KO docs 업데이트가 필요하면 existing KO files를 읽지 않고 canonical `.md` files에서 재생성한다.
- destructive database work, mass file moves/deletions, V2 scope expansion, secret/env changes, deployment/network actions 전에는 질문한다.
- Markdown changes 후에는 `scripts/check-ko-docs.sh`를 실행한다.
