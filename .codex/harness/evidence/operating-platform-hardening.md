# 운영환경 고도화 TDD 근거

## Inbox pagination overflow

- 최초 RED: `./gradlew :core:test --tests 'com.home.infrastructure.persistence.user.JdbcInsightRepositoryTest.returnsEmptyInboxForLargeValidPage' --no-daemon --stacktrace`가 `ArithmeticException`으로 실패했다.
- 예상 RED 실패: 계약상 유효한 `page=Integer.MAX_VALUE`, `size=100`의 offset을 `int`로 계산해 overflow가 발생했다.
- 최소 GREEN: offset을 64-bit로 계산하도록 변경한 뒤 동일 명령이 `BUILD SUCCESSFUL`로 통과했다.

## Isolated Compose validation

- 최초 RED: 별도 publication clone에서 `infra/test-compose-config.sh`가 clone에 없는 local `.env` 기본 경로 때문에 실패했다.
- 예상 RED 실패: 검증기가 개발자 checkout의 비추적 secret 파일 존재에 의존했다.
- 최소 GREEN: 기본 경로는 유지하면서 local env file 경로만 환경 변수로 override할 수 있게 했고, `/dev/null` override로 Compose config 검증을 통과했다. 실제 secret 파일은 읽거나 복사하지 않았다.

## Diff hygiene

- 최초 RED: `git diff --check main...HEAD`가 Markdown 4개 파일의 EOF 공백을 보고했다.
- 예상 RED 실패: canonical Markdown 끝에 불필요한 빈 줄이 포함됐다.
- 최소 GREEN: EOF 공백만 제거하고 동일 diff 검사를 통과했다.
