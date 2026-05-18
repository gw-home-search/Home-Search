# Claude 설정 한국어 참조

> 사람 전용 한국어 참조 문서입니다. 기준 문서는 `CLAUDE.md`입니다.

## 요약

`CLAUDE.md`는 Claude Code가 루트 `AGENTS.md`를 기준 규칙으로 가져오게 하는 얇은 호환 파일이다.

Claude는 canonical Markdown 파일, source code, `.ko-docs.toml`을 기준으로 작업한다.

## KO 문서 업데이트

KO 문서를 갱신해야 할 때는 기존 KO 파일을 읽지 않고, 대응 원문 `.md`를 기준으로 새로 작성한다.

## 주의할 작업

다음 작업은 사용자 확인이 필요하다.

- 파괴적인 DB 작업
- 대량 파일 이동 또는 삭제
- V2 범위 확장
- secret/env 변경
- 배포 또는 외부 네트워크 작업

Markdown 변경 후에는 `scripts/check-ko-docs.sh`로 KO 문서 동기화를 확인한다.
