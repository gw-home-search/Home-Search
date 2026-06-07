INSERT INTO news_source_policy (
    source,
    source_class,
    usage_status,
    full_text_allowed,
    replacement_summary_allowed,
    terms_url,
    notes
)
VALUES (
    'NAVER_NEWS',
    'SEARCH_API',
    'ALLOWED',
    false,
    false,
    'https://developers.naver.com/docs/serviceapi/search/news/news.md',
    'metadata and snippet only'
)
ON CONFLICT (source) DO NOTHING;
