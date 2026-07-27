\set ON_ERROR_STOP on

SELECT repair.collection_id,
       repair.source_collection_id,
       repair.repair_policy_version,
       repair.status,
       repair.target_count,
       repair.request_count,
       repair.completed_count,
       repair.failure_count,
       count(*) FILTER (WHERE sample.collection_status='PENDING') AS pending_pnu_count,
       count(*) FILTER (WHERE sample.collection_status='COLLECTED') AS collected_pnu_count,
       count(*) FILTER (WHERE sample.collection_status='FAILED') AS failed_pnu_count
FROM building_register_profile_repair_run repair
JOIN building_register_profile_sample_pnu sample
  ON sample.collection_id=repair.collection_id
WHERE repair.collection_id=CAST(:'collection_id' AS uuid)
GROUP BY repair.collection_id;

SELECT snapshot.endpoint,
       snapshot.status,
       count(*) AS snapshot_count,
       max(snapshot.attempt_no) AS maximum_attempt
FROM building_register_endpoint_snapshot snapshot
WHERE snapshot.collection_id=CAST(:'collection_id' AS uuid)
GROUP BY snapshot.endpoint,snapshot.status
ORDER BY snapshot.endpoint,snapshot.status;

SELECT count(*) FILTER (WHERE raw.status IN ('PARSED','EMPTY')) AS completed_raw_page_count,
       count(*) FILTER (WHERE raw.status='PROVIDER_FAILED') AS provider_failed_raw_page_count,
       count(*) FILTER (WHERE raw.response_body IS NOT NULL) AS retained_body_count,
       max(raw.byte_count) AS maximum_response_bytes
FROM building_register_raw_page raw
JOIN building_register_endpoint_snapshot snapshot ON snapshot.id=raw.endpoint_snapshot_id
WHERE snapshot.collection_id=CAST(:'collection_id' AS uuid);
