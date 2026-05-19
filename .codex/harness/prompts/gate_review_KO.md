# Gate Review Prompt 한국어 동기화본

$v1-slice-harness mode=gate

Slice: {{SLICE}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

리뷰만 수행한다. 파일을 수정하지 않는다.

확인할 것:
- 변경 범위가 target app 내부에 머물렀는지 확인한다.
- V1 API contract와 data invariant가 보존됐는지 확인한다.
- 필수 verification evidence가 있는지 확인한다.
- protected paths, secrets, build output, automatic main merge, push가 들어가지 않았는지 확인한다.

다음 labels로 짧은 한국어 gate review를 출력한다:
- 상태: Pass|Partial|Fail
- First RED:
- Expected RED failure:
- Minimum GREEN:
- 검증:
- 리뷰:
- 주요 위험:
- 다음 행동:
