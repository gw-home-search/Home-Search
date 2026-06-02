# RTMS Jibun and PNU Matching Policy


## Goal

RTMS apartment trade rows must not pollute public map/detail trade display when
the source parcel or apartment-complex match is uncertain.

This document fixes how Home Search reads RTMS land-address fields, derives a
PNU, records match evidence, decides whether a row can enter normalized `trade`,
and leaves reviewable evidence for later administrator tooling.

## Public API Boundary

This policy does not change public API URLs, request fields, response fields,
amount units, coordinate units, or `ProblemDetail` behavior documented in
`API_CONTRACT.md`.

The policy affects only ingest, matching, evidence storage, and future
administrator review. Public map/detail/trade endpoints continue to read only
normalized display-safe `trade` rows.

## RTMS Source Fields

Home Search derives parcel evidence from each RTMS response item, not from UI or
manual input.

Canonical source mapping:

| RTMS JSON item field | Home Search field | Purpose |
| --- | --- | --- |
| `item.jibun` | `OpenApiTradeItem.jibun` | raw land lot text |
| `item.sggCd` | `OpenApiTradeItem.sggCd` | legal district prefix |
| `item.umdCd` | `OpenApiTradeItem.umdCd` | legal dong code |
| `item.aptNm` | `OpenApiTradeItem.aptName` | observed apartment name |
| `item.aptSeq` | `OpenApiTradeItem.aptSeq` | RTMS apartment sequence |

The request-level `LAWD_CD` remains batch metadata. It must not silently replace
missing item-level `sggCd` in this policy slice. If item-level district fields
are missing or invalid, the row is held for review with `PNU_UNAVAILABLE`.

## Jibun Normalization

The normalizer stores both the source value and the parsed parts used to create
`derived_pnu`.

Fields:

- `raw_jibun`: RTMS `item.jibun` after trim.
- `normalized_jibun`: compact jibun text used for parsing.
- `sgg_cd`: RTMS `item.sggCd`, required 5 digits.
- `umd_cd`: RTMS `item.umdCd`, required 5 digits.
- `land_code`: `2` when the raw jibun includes `산`; otherwise `1`.
- `bonbun`: main lot number, four-digit zero padded.
- `bubun`: sub lot number, four-digit zero padded; `0000` when absent.
- `derived_pnu`: `sgg_cd + umd_cd + land_code + bonbun + bubun`.
- `pnu_unavailable_reason`: machine-readable reason when PNU cannot be derived.

Examples:

| `sggCd` | `umdCd` | `jibun` | `derived_pnu` |
| --- | --- | --- | --- |
| `11680` | `10300` | `140-1` | `1168010300101400001` |
| `11680` | `10300` | `140` | `1168010300101400000` |
| `11680` | `10300` | `산 12-3` | `1168010300200120003` |

Invalid cases include blank `jibun`, non-numeric lot parts, lot parts longer
than four digits, and non-5-digit `sggCd` or `umdCd`.

## Display-Safe Trade Rule

Normalized `trade` is a public display source. It feeds latest marker prices,
detail drawers, and trade lists. Therefore, uncertain matches must not be
inserted into normalized `trade`.

Raw ingest evidence is always saved first. Parsed non-duplicate rows then create
match evidence. Only rows with a safe match are inserted into normalized
`trade`.

This document decides whether a row is safe to match to a complex. The storage
identity, duplicate, `apt_dong`, and cancellation rules are fixed in
[DATA_STORAGE.md](DATA_STORAGE.md#deduplication).

## Match Status Decision Table

| Situation | Normalized `trade` | Evidence status | Notes |
| --- | --- | --- | --- |
| `aptSeq` unique, derived PNU matches the complex parcel PNU, and name matches master or alias | insert | `MATCHED` | strongest safe path |
| `aptSeq` unique, derived PNU matches, but RTMS name is a new variant | insert | `MATCHED_NAME_VARIANT` | keep master name; preserve observed name as alias evidence |
| `aptSeq` unique, derived PNU differs only by the first 5-digit `sggCd`, the complex parcel PNU starts with the `aptSeq` prefix, and RTMS name matches master or alias | insert | `MATCHED_PNU_SGG_CORRECTED` | handles observed RTMS item `sggCd` drift while preserving the raw derived PNU evidence |
| `aptSeq` unique, derived PNU does not match the complex parcel PNU | hold | `PNU_CONFLICT` | prevents latest price from attaching to the wrong parcel |
| PNU has one complex and RTMS name matches master or alias | insert | `MATCHED` | safe PNU/name path |
| PNU has one complex but RTMS name does not match master or alias | hold | `NAME_CONFLICT` | same land lot can still contain name ambiguity |
| PNU has many complexes and name or alias selects exactly one | insert | `MATCHED` | safe disambiguated path |
| PNU has many complexes and name or alias cannot select one | hold | `AMBIGUOUS` | no arbitrary candidate selection |
| PNU cannot be derived | hold | `PNU_UNAVAILABLE` | missing/invalid district or jibun parts |
| PNU is derived but no parcel/complex candidate exists | hold | `UNMATCHED` | future coordinate/master bootstrap or admin review can resolve it |

`hold` means the raw row and match evidence remain queryable, but the row does
not affect public latest price, detail, or trade-list APIs.

## Evidence Storage

Match evidence should be queryable by `raw_ingest_id`.

Minimum evidence fields:

- `raw_ingest_id`
- `source`
- `raw_jibun`
- `normalized_jibun`
- `sgg_cd`
- `umd_cd`
- `land_code`
- `bonbun`
- `bubun`
- `derived_pnu`
- `pnu_unavailable_reason`
- `apt_seq`
- `apt_name`
- `match_status`
- `match_path`
- `matched_complex_id`
- `matched_complex_pk`
- `candidate_count`
- `candidate_complex_ids`
- `failure_reason`
- `created_at`

Evidence must not expose raw payload text, service keys, local secrets, or the
full `source_key` in default review queries or logs.

## Administrator Review Boundary

This policy creates the backend evidence needed for future administrator review;
it does not implement an admin UI.

Future review queues should include:

- `PNU_UNAVAILABLE`
- `UNMATCHED`
- `AMBIGUOUS`
- `NAME_CONFLICT`
- `PNU_CONFLICT`
- `MATCHED_NAME_VARIANT`
- `MATCHED_PNU_SGG_CORRECTED`

Future admin detail views should show:

- deal date and amount summary
- RTMS `aptNm`, `aptSeq`, raw/normalized `jibun`, and `derived_pnu`
- match status, match path, candidate count, and failure reason
- candidate complexes with `complexId`, `complexPk`, names, aliases, parcel PNU,
  address, and unit count
- whether the row is currently included in public latest trade display

Future admin actions should be separate slices:

- confirm an existing complex match and replay normalization
- preserve an observed RTMS name as an alias
- keep the row held for review
- mark the row invalid or ignored
- create new parcel/complex master data only through a high-risk data slice

All admin actions need audit metadata. The current policy slice only creates
the evidence foundation.

## Test Coverage Requirements

The implementation must cover each policy branch with deterministic tests:

- Jibun/PNU unit tests for normal, mountain-lot, missing, invalid, and too-long
  parts.
- Repository tests for match evidence insert/query and sensitive-field
  exclusion.
- Integration tests showing held rows do not create normalized `trade`.
- Integration tests showing safe rows create normalized `trade` and evidence.
- Regression tests for `PNU_CONFLICT`, `NAME_CONFLICT`, `AMBIGUOUS`,
  `PNU_UNAVAILABLE`, `UNMATCHED`, `MATCHED`, `MATCHED_NAME_VARIANT`, and
  `MATCHED_PNU_SGG_CORRECTED`.

No test may require live RTMS, VWorld, Kakao, production data, secrets, or a
manual admin UI.
