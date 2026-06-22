# News Local Input

This directory is for local-only source files used during region-month signal
seed experiments.

Put historical news CSV files under:

```text
apps/news/local-input/historical-news-csv/
```

Files in that directory are intentionally ignored by git. Keep original CSV data
local. The aggregate generator reads metadata columns only and writes no article
body or article note files.

Generated local-only aggregate inputs:

```text
apps/news/local-input/region-month-signal-bigkinds.csv.jsonl
apps/news/local-input/region-month-signal-web-worklist.jsonl
apps/news/local-input/region-month-signal-web-research.jsonl
```

`region-month-signal-web-research.jsonl` must contain scored aggregate rows, not
article body text or article-like summaries.

Evidence target by source:

- `BIGKINDS_CSV`: up to 10 metadata evidence links per region-month aggregate.
- `AGENT_WEB_RESEARCH`: target 5 metadata evidence links per region-month
  aggregate; inherited evidence is allowed when direct local evidence is sparse.
