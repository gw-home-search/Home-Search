GRANT SELECT
ON TABLE public.event_outbox
TO home_search_property_runtime;

GRANT INSERT (
    event_id,
    topic_name,
    event_type,
    schema_version,
    occurred_at,
    producer,
    aggregate_type,
    aggregate_id,
    aggregate_version,
    correlation_id,
    causation_id,
    trace_id,
    payload
)
ON TABLE public.event_outbox
TO home_search_property_runtime;

GRANT UPDATE (
    published_at,
    attempt_count,
    next_attempt_at,
    last_error
)
ON TABLE public.event_outbox
TO home_search_property_runtime;
