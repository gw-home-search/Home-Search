CREATE TABLE public.event_outbox (
    event_id uuid PRIMARY KEY,
    topic_name varchar(128) NOT NULL,
    event_type varchar(64) NOT NULL,
    schema_version smallint NOT NULL,
    occurred_at timestamptz NOT NULL,
    producer varchar(64) NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    aggregate_version bigint NOT NULL,
    correlation_id varchar(128) NOT NULL,
    causation_id varchar(128),
    trace_id varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(256),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_outbox_aggregate_version
        UNIQUE (event_type, aggregate_id, aggregate_version),
    CONSTRAINT ck_event_outbox_topic
        CHECK (topic_name IN (
            'property.trade-events.v1',
            'property.complex-events.v1',
            'property.insight-events.v1'
        )),
    CONSTRAINT ck_event_outbox_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_event_outbox_aggregate_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_event_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_event_outbox_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_event_outbox_payload_size
        CHECK (octet_length(payload::text) <= 262144),
    CONSTRAINT ck_event_outbox_publication
        CHECK (published_at IS NULL OR published_at >= occurred_at)
);

CREATE INDEX ix_event_outbox_unpublished
    ON public.event_outbox (next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL;

CREATE INDEX ix_event_outbox_published_retention
    ON public.event_outbox (published_at, event_id)
    WHERE published_at IS NOT NULL;
