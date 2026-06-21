# News Runtime Storage Execute Prompt

home-search-harness mode=execute

Work item: {{WORK_ID}}
Preset: {{PRESET}}
Target: {{TARGET}}
Branch: {{BRANCH_NAME}}

Allowed edit scope:
- {{ALLOWED_SCOPE}}

Forbidden edit scope:
- {{FORBIDDEN_SCOPE}}
- secrets, `.env*`, provider response payload files, Docker volume deletion, DB reset

Instructions:
- Read repository agent rules and canonical docs before editing.
- Treat `apps/news` as later-scope and keep public map/search/region/detail/trade APIs unchanged.
- Do not make live Naver, OpenAI, RTMS, or other provider calls in this PR gate.
- Use fake providers and Testcontainers PostgreSQL for storage invariants.
- Verify Naver metadata can be saved as `article_observation` even when `home.news.openai.enabled=false`.
- Preserve `source + source_key` dedupe and `first_seen_at` across duplicate collection.
- Preserve `collection_run_article.article_observation_id` after scoring failures.
- Do not add DB migrations, historical CSV importer work, API changes, secrets, or build output.
- Use the minimum GREEN work item and leave a short Korean-first gate summary.

Skill routing:
{{SKILL_ROUTING}}

Required verification:
- {{VERIFICATION_COMMANDS}}
- Use exact evidence line format: ``- `command` = pass|fail|not run (Korean reason)``.

Final user-facing evidence labels:
- 상태:
- 최초 RED:
- 예상 RED 실패:
- 최소 GREEN:
- 검증:
- 계약 영향:
- 보안 영향:
- security-audit: 지적사항 = none|listed
- 주요 위험:
- 다음 행동:
