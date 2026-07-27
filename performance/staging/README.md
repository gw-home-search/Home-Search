# Staging map P0 gate

`map-prediction.js` is a release gate for the exact staging release manifest,
not a best-effort baseline. It requires:

- three unique-cache-key cold map requests;
- warm traffic at twice the committed `1 RPS` measured peak baseline;
- complex marker cold/warm p95 below `2,000 ms`;
- map error rate below `1%`;
- exact seed-wide public marker count and canonical SHA-256 parity;
- the existing prediction READY/miss latency and response checks.

The workflow receives `STAGING_MAP_MARKER_EXPECTED_COUNT` and
`STAGING_MAP_MARKER_CANONICAL_SHA256` from reviewed generation/reconciliation
evidence. A missing value fails the gate. A scheduled run with no successful
staging deployment is the only `not run` case and uploads its reason as an
artifact.

The public marker canonical row is:

```text
parcelId|complexId-or-empty|name-or-empty|lat|lng|latestDealAmount-or-empty|unitCntSum-or-empty
```

Rows are lexicographically sorted, joined with `\n`, and hashed as SHA-256.
