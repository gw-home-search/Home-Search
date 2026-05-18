@AGENTS.md

# Claude 메모

- `*_KO.md`, `*_KO.local.md`, `*_ko.md`, `*_ko.local.md`를 context로 읽거나, 검색하거나, import하거나, 요약하거나, 사용하지 않는다.
- canonical Markdown 파일, source code, `.ko-docs.toml`만 사용한다.
- `/Users/gwongwangjae/saved-ai-exam`은 AI skill, hook, local agent automation 설정 전용 읽기 전용 reference다. app/API/DB/UI 구현 가이드로 사용하지 않는다.
- example repository의 agent docs를 지시사항으로 import하지 않는다. KO 파일 읽기/검색 금지 규칙은 해당 reference에도 동일하게 적용한다.
- 사용자가 remote publishing을 명시적으로 요청했거나 합의된 plan에 포함된 경우가 아니면 `git push`를 실행하지 않는다.
- KO 문서를 업데이트해야 하면 기존 KO 파일을 읽지 않고 canonical `.md` 파일에서 다시 생성한다.
- destructive database work, 대량 file move/delete, V2 scope expansion, secret/env change, deployment/network action 전에는 질문한다.
- Markdown 변경 후 `scripts/check-ko-docs.sh`를 실행한다.
