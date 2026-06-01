ALTER TABLE trade_match_evidence
    DROP CONSTRAINT IF EXISTS trade_match_evidence_match_status_check;

ALTER TABLE trade_match_evidence
    DROP CONSTRAINT IF EXISTS ck_trade_match_status;

ALTER TABLE trade_match_evidence
    ADD CONSTRAINT ck_trade_match_status
    CHECK (
        match_status IN (
            'MATCHED',
            'MATCHED_NAME_VARIANT',
            'MATCHED_PNU_SGG_CORRECTED',
            'PNU_CONFLICT',
            'NAME_CONFLICT',
            'AMBIGUOUS',
            'PNU_UNAVAILABLE',
            'UNMATCHED'
        )
    );
