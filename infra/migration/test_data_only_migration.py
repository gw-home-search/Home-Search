from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import data_only_migration as migration


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "infra/migration/data-only-allowlist.json"


class CatalogValidationTest(unittest.TestCase):
    def test_checked_in_catalog_preserves_required_data_order(self) -> None:
        catalog = migration.load_catalog(CATALOG)
        names = [migration.dataset_name(item) for item in catalog["datasets"]]

        self.assertEqual(len(names), 100)
        self.assertIn("property:public.building_register_record_snapshot", names)
        self.assertIn("property:public.market_news_raw_item", names)
        self.assertTrue(all(item["conflictPolicy"] == "update" for item in catalog["datasets"] if item["logicalDatabase"] == "property"))
        self.assertEqual(
            next(item for item in catalog["datasets"] if migration.dataset_name(item) == "reference:public.dataset_active_snapshot")["conflictPolicy"],
            "update",
        )
        self.assertLess(names.index("property:public.raw_trade_ingest"), names.index("property:public.trade"))
        self.assertLess(names.index("property:public.trade"), names.index("property:public.trade_source_key_registry"))
        self.assertLess(names.index("reference:public.dataset_raw_object"), names.index("reference:public.dataset_publication"))
        self.assertLess(
            names.index("reference:public.dataset_publication"),
            names.index("reference:reference_projection.facility_point"),
        )
        self.assertEqual(names[-1], "reference:public.dataset_active_snapshot")

    def test_catalog_structurally_excludes_identity_and_schema_state(self) -> None:
        catalog = migration.load_catalog(CATALOG)
        for item in catalog["datasets"]:
            self.assertIn(item["logicalDatabase"], {"property", "reference"})
            self.assertNotIn(
                item["table"],
                {"user_account", "admin_account", "session", "token", "flyway_schema_history", "ai_schema_history"},
            )

    def test_wildcard_or_duplicate_order_is_rejected(self) -> None:
        invalid = {
            "formatVersion": 1,
            "datasets": [
                {
                    "order": 10,
                    "logicalDatabase": "property",
                    "conflictPolicy": "update",
                    "schema": "public",
                    "table": "raw_trade_ingest",
                    "columns": ["*"],
                    "keyColumns": ["id"],
                    "chunkKey": "id",
                    "chunkRows": 100,
                },
                {
                    "order": 10,
                    "logicalDatabase": "property",
                    "conflictPolicy": "update",
                    "schema": "public",
                    "table": "trade",
                    "columns": ["id", "deal_date"],
                    "keyColumns": ["id", "deal_date"],
                    "chunkKey": "id",
                    "chunkRows": 100,
                },
            ],
        }
        with self.assertRaisesRegex(migration.MigrationError, "column|order"):
            migration.validate_catalog(invalid)


class ManifestValidationTest(unittest.TestCase):
    def test_zstd_threads_default_to_one_and_reject_unbounded_values(self) -> None:
        self.assertEqual(migration.zstd_threads({}), "1")
        self.assertEqual(migration.zstd_threads({"HOME_MIGRATION_ZSTD_THREADS": "4"}), "4")

        for value in ("0", "9", "all", "-1"):
            with self.subTest(value=value), self.assertRaisesRegex(migration.MigrationError, "ZSTD_THREADS"):
                migration.zstd_threads({"HOME_MIGRATION_ZSTD_THREADS": value})

    def test_s3_publication_requires_uri_and_kms_key_together(self) -> None:
        migration.validate_s3_options(None, None)
        migration.validate_s3_options("s3://migration/release", "alias/migration")

        with self.assertRaisesRegex(migration.MigrationError, "together"):
            migration.validate_s3_options("s3://migration/release", None)
        with self.assertRaisesRegex(migration.MigrationError, "together"):
            migration.validate_s3_options(None, "alias/migration")

    def test_child_process_environment_removes_migration_passwords(self) -> None:
        os.environ["HOME_MIGRATION_PROPERTY_SOURCE_PASSWORD"] = "sentinel"
        try:
            self.assertNotIn("HOME_MIGRATION_PROPERTY_SOURCE_PASSWORD", migration.sanitized_environment())
        finally:
            os.environ.pop("HOME_MIGRATION_PROPERTY_SOURCE_PASSWORD", None)

    def test_tampered_chunk_is_rejected_before_import(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            chunk = root / "property-public-region-000001.csv.zst"
            chunk.write_bytes(b"fixture")
            manifest = {
                "formatVersion": 1,
                "migrationId": "20260728T010203Z-0123456789abcdef",
                "catalogSha256": "a" * 64,
                "datasets": [{"dataset": "property:public.region", "rowCount": 1}],
                "chunks": [
                    {
                        "dataset": "property:public.region",
                        "file": chunk.name,
                        "sha256": hashlib.sha256(b"different").hexdigest(),
                        "csvSha256": hashlib.sha256(b"fixture csv").hexdigest(),
                        "rowCount": 1,
                        "minKey": "1",
                        "maxKey": "1",
                        "sourceWatermark": "0/1234",
                    }
                ],
            }
            path = root / "manifest.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaisesRegex(migration.MigrationError, "checksum"):
                migration.validate_manifest_artifacts(path, expected_catalog_sha256="a" * 64)

    def test_manifest_rejects_unexpected_dataset_and_path_traversal(self) -> None:
        manifest = {
            "formatVersion": 1,
            "migrationId": "20260728T010203Z-0123456789abcdef",
            "catalogSha256": "b" * 64,
            "datasets": [{"dataset": "property:public.region", "rowCount": 1}],
            "chunks": [
                {
                    "dataset": "user:users.user_account",
                    "file": "../user.csv.zst",
                    "sha256": "c" * 64,
                    "rowCount": 1,
                    "minKey": "1",
                    "maxKey": "1",
                    "sourceWatermark": "0/1234",
                }
            ],
        }
        with self.assertRaises(migration.MigrationError):
            migration.validate_manifest(manifest, {"property:public.region"})


if __name__ == "__main__":
    unittest.main()
