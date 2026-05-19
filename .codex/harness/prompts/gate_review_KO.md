# 게이트 리뷰 프롬프트

$v1-slice-harness mode=gate

Slice: {{SLICE}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

리뷰만 수행하세요. 파일을 수정하지 마세요.

확인 항목:
- 범위가 대상 preset의 허용 수정 범위 안에 머물렀는지 확인합니다.
- V1 API 계약과 데이터 불변식이 보존되었는지 확인합니다.
- 필수 검증 evidence가 있는지 확인합니다.
- 검증 evidence가 정확한 line format을 사용하는지 확인합니다: ``- `command` = pass|fail|not run (Korean reason)``.
- 보호 경로, secret, build output, 자동 main merge, push가 도입되지 않았는지 확인합니다.

아래 사용자 표시 label을 사용해 짧은 Korean-first 게이트 리뷰를 출력하세요:
- 상태: Pass|Partial|Fail
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 리뷰:
- 주요 위험:
- 다음 행동:
