# ADR 0007: Kafka-first asynchronous state and HTTP exceptions

- Status: Accepted
- Date: 2026-07-25

## Context

Property, user, and AI services need asynchronous state propagation without
cross-service database reads. Browser requests, admin commands, chatbot
streaming, and ML inference still require an immediate response.

## Decision

- Use MSK Serverless for asynchronous state changes only.
- Keep browser/public API, admin RS256 commands, chatbot JSON/SSE, and ML
  inference on HTTP.
- Use transactional outbox, idempotent inbox, monotonic aggregate versions,
  bounded retry, and DLQ.
- Guarantee at-least-once delivery; do not claim exactly-once processing.
- Use plain JSON wire messages validated against repository-owned JSON Schema.
  Glue is the compatibility/governance registry, not runtime auto-registration.
- Do not put raw provider data, `source_key`, email, tokens, prompts, answers,
  URLs containing keys, or credentials in events.

## Consequences

Producer database commits can continue while Kafka is unavailable because the
outbox is durable. Consumers must tolerate duplicates, stale versions, restart,
and replay. Kafka is not a replacement for synchronous request/response.

## Rollback

Disable producer relay by feature flag while preserving outbox rows. Existing
HTTP paths remain available. Topic, schema version, outbox, inbox, and DLQ data
are not deleted during rollback.

