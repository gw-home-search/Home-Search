CREATE OR REPLACE FUNCTION public.delete_published_property_event_outbox_before(
    p_cutoff timestamp with time zone,
    p_limit integer
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    deleted_count integer;
BEGIN
    IF p_cutoff IS NULL THEN
        RAISE EXCEPTION 'p_cutoff is required';
    END IF;
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'p_limit must be between 1 and 1000';
    END IF;

    WITH expired AS (
        SELECT event_id
        FROM public.event_outbox
        WHERE published_at IS NOT NULL
          AND published_at < p_cutoff
        ORDER BY published_at, event_id
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    DELETE FROM public.event_outbox AS event
    USING expired
    WHERE event.event_id = expired.event_id;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

REVOKE ALL
ON FUNCTION public.delete_published_property_event_outbox_before(timestamp with time zone, integer)
FROM PUBLIC;

GRANT EXECUTE
ON FUNCTION public.delete_published_property_event_outbox_before(timestamp with time zone, integer)
TO home_search_property_runtime;
