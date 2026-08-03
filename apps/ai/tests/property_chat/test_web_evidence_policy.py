from ai_service.property_chat.web_evidence import (
    WebEvidenceMode,
    WebEvidencePolicy,
    validate_official_source_url,
)


def test_web_policy_is_disabled_for_ledger_and_required_for_current_plans() -> None:
    policy = WebEvidencePolicy()

    assert policy.classify("반포자이 최근 실거래 알려줘", internal_axis_count=1) is WebEvidenceMode.DISABLED
    assert policy.classify("잠실 재건축 최신 공고 알려줘", internal_axis_count=4) is WebEvidenceMode.REQUIRED
    assert policy.classify("송파 아파트 추천", internal_axis_count=2) is WebEvidenceMode.ALLOWED
    assert policy.classify("송파 아파트 추천", internal_axis_count=4) is WebEvidenceMode.DISABLED
    assert policy.classify("잠실 정비사업 알려줘", internal_axis_count=1) is WebEvidenceMode.DISABLED


def test_only_credential_free_https_allowlist_urls_are_accepted() -> None:
    assert validate_official_source_url("https://www.reb.or.kr/r-one")
    assert validate_official_source_url("https://data.seoul.go.kr/doc?a=1")
    assert not validate_official_source_url("http://www.reb.or.kr/r-one")
    assert not validate_official_source_url("https://evil.example/news")
    assert not validate_official_source_url("https://user:secret@www.reb.or.kr/r-one")
    assert not validate_official_source_url("https://www.reb.or.kr/r-one?token=secret")
    assert not validate_official_source_url(
        "https://www.reb.or.kr/r-one?access_token=secret"
    )
    assert not validate_official_source_url(
        "https://www.reb.or.kr/r-one?X-Amz-Signature=secret"
    )


def test_official_url_rejects_malformed_ipv6_authority() -> None:
    assert validate_official_source_url("https://[invalid/notice") is False
