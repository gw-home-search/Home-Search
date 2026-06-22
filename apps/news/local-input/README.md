# News Local Input

This directory is for local-only source files used during news seed experiments.

Put historical news CSV files under:

```text
apps/news/local-input/historical-news-csv/
```

Files in that directory are intentionally ignored by git. Keep original CSV data
local, inspect its columns first, then build a dedicated importer in a separate
slice.

Monthly CSV shortlist selection is also local-only. After an operator chooses
shortlist numbers, selected `source_key` values can be staged in:

```text
apps/news/local-input/historical-news-csv-selected.txt
```
