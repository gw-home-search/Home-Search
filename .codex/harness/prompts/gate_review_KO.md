# Gate Review Prompt


$v1-slice-harness mode=gate

Slice: {{SLICE}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

리뷰만 수행한다. 파일을 수정하지 않는다.

Skill routing:
{{SKILL_ROUTING}}

확인 항목:
- 변경 범위가 target preset의 허용 edit scope 안에 머물렀다.
- V1 API contract와 data invariant가 보존되었다.
- 필요한 verification evidence가 있다.
- Verification evidence는 정확한 line format을 사용한다: ``- `command` = pass|fail|not run (Korean reason)``.
- backend, frontend, harness, hook, GitHub workflow, Markdown, KO 변경에 대한
  changed-file PR lint evidence가 있다.
- protected path, secrets, build output, automatic main merge, main push,
  PR merge가 추가되지 않았다.
- 명시적 `--pr`은 생성된 `feat/*-integration` branch만 push할 수 있다.

다음 user-facing label로 짧은 Korean-first gate review를 출력한다:
- 상태: Pass|Partial|Fail
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 리뷰:
- 주요 위험:
- 다음 행동:
