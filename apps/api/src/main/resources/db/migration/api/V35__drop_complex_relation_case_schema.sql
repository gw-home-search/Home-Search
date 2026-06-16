DO $$
DECLARE
    relation_case_count BIGINT;
    relation_case_complex_count BIGINT;
BEGIN
    IF to_regclass('public.complex_relation_case_complex') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM complex_relation_case_complex'
            INTO relation_case_complex_count;
        IF relation_case_complex_count > 0 THEN
            RAISE EXCEPTION
                'complex_relation_case_complex is not empty: % rows',
                relation_case_complex_count;
        END IF;
    END IF;

    IF to_regclass('public.complex_relation_case') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM complex_relation_case'
            INTO relation_case_count;
        IF relation_case_count > 0 THEN
            RAISE EXCEPTION
                'complex_relation_case is not empty: % rows',
                relation_case_count;
        END IF;
    END IF;
END $$;

DROP TABLE IF EXISTS complex_relation_case_complex;
DROP TABLE IF EXISTS complex_relation_case;
