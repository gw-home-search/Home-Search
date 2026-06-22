query_month: {query_month}
month_start: {month_start}
month_end: {month_end}
region_bucket: {region_bucket}
region_bucket_ko: {region_bucket_ko}
region_aliases: {region_aliases}
target_candidates: {target_candidates}

topic_search_profiles:
{topic_search_profiles}

accepted_signal_examples:
{accepted_signal_examples}

reject_rules:
{reject_rules}

Search and screen in three passes.
Pass 1: Search the exact month, region, and policy or market keyword directly.
Pass 2: Vary region aliases and topic keywords. Prefer original publisher pages and public agency releases.
Pass 3: Rejudge each candidate as an apartment price prediction feature. Drop weak candidates.

Include candidates about policy changes, tax, loan, interest rates, subscription, supply, occupancy, unsold inventory, reconstruction, redevelopment permits, transport confirmation, construction, opening, school district policy, jeonse shocks, transaction volume shocks, auctions, distress, and large development projects.

format: Return only the separate strict JSON object. Do not include markdown, prose, article body, summary, or copied article text.
- Candidate url and url_citation must be direct http(s) URLs from the cited source or search result.
- Do not use citation labels, search queries, bracket references, relative paths, or publisher names as url or url_citation.
- If a direct http(s) URL cannot be verified, reject the candidate instead of guessing.
