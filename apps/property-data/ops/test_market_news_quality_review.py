#!/usr/bin/env python3

import importlib.util
import csv
import pathlib
import sys
import tempfile
import unittest
from unittest import mock


MODULE_PATH = pathlib.Path(__file__).with_name("market_news_quality_review.py")
SPEC = importlib.util.spec_from_file_location("market_news_quality_review", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def passing_metrics(**overrides):
    values = {
        "sample_status": "READY",
        "source_snapshot_count": 18,
        "elapsed_hours": 200.0,
        "healthy_general_runs": 32,
        "relevance_reviewed": 180,
        "relevance_correct": 171,
        "category_metrics": {category: (30, 28) for category in MODULE.CATEGORIES},
        "relation_metrics": {
            "DIRECT_COMPLEX": (60, 58),
            "SAME_DONG": (40, 37),
            "SAME_SIGUNGU": (40, 37),
        },
        "challenge_reviewed": 50,
        "challenge_false_direct": 0,
        "url_reviewed": 100,
        "url_opened": 99,
    }
    values.update(overrides)
    return MODULE.QualityMetrics(**values)


class QualityAssessmentTest(unittest.TestCase):

    def test_passes_immediate_quality_thresholds(self):
        self.assertEqual("PASS", MODULE.assess(passing_metrics(), "immediate").status)

    def test_insufficient_sample_never_passes(self):
        result = MODULE.assess(passing_metrics(sample_status="INSUFFICIENT_SAMPLE"), "immediate")
        self.assertEqual("INSUFFICIENT_SAMPLE", result.status)

    def test_missing_human_labels_remain_pending(self):
        result = MODULE.assess(passing_metrics(relevance_reviewed=179), "immediate")
        self.assertEqual("PENDING_HUMAN_REVIEW", result.status)

    def test_false_challenge_relation_fails_quality(self):
        result = MODULE.assess(passing_metrics(challenge_false_direct=1), "immediate")
        self.assertEqual("FAIL", result.status)

    def test_24_hour_checkpoint_waits_for_time_and_four_runs(self):
        self.assertEqual(
            "PENDING_TIME",
            MODULE.assess(passing_metrics(elapsed_hours=23.9), "24h").status,
        )
        self.assertEqual(
            "PENDING_OPERATIONAL_RUNS",
            MODULE.assess(passing_metrics(elapsed_hours=24.0, healthy_general_runs=3), "24h").status,
        )
        self.assertEqual(
            "PASS",
            MODULE.assess(passing_metrics(elapsed_hours=24.0, healthy_general_runs=4), "24h").status,
        )

    def test_7_day_checkpoint_requires_168_hours_and_28_runs(self):
        self.assertEqual(
            "PENDING_TIME",
            MODULE.assess(passing_metrics(elapsed_hours=167.9), "7d").status,
        )
        self.assertEqual(
            "PENDING_OPERATIONAL_RUNS",
            MODULE.assess(passing_metrics(elapsed_hours=168.0, healthy_general_runs=27), "7d").status,
        )
        self.assertEqual(
            "PASS",
            MODULE.assess(passing_metrics(elapsed_hours=168.0, healthy_general_runs=28), "7d").status,
        )

    def test_import_dry_run_validates_csv_and_rolls_back(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "labels.csv"
            with source.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=(
                        "article_id",
                        "relation_id",
                        "relevance_correct",
                        "category_correct",
                        "relation_correct",
                        "url_opened",
                        "note",
                    ),
                )
                writer.writeheader()
                writer.writerow(
                    {
                        "article_id": "1",
                        "relation_id": "2",
                        "relevance_correct": "true",
                        "category_correct": "true",
                        "relation_correct": "false",
                        "url_opened": "true",
                        "note": "reviewed",
                    }
                )
            with mock.patch.object(MODULE, "run_psql") as run_psql:
                MODULE.import_labels(
                    "123e4567-e89b-12d3-a456-426614174000",
                    "reviewer-1",
                    source,
                    True,
                )
            sql = run_psql.call_args.args[2]
            self.assertIn("ROLLBACK;", sql)
            self.assertNotIn("COMMIT;", sql)

    def test_import_rejects_duplicate_article_relation_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "labels.csv"
            with source.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=(
                        "article_id",
                        "relation_id",
                        "relevance_correct",
                        "category_correct",
                        "relation_correct",
                        "url_opened",
                        "note",
                    ),
                )
                writer.writeheader()
                for correctness in ("true", "false"):
                    writer.writerow(
                        {
                            "article_id": "1",
                            "relation_id": "2",
                            "relevance_correct": correctness,
                            "category_correct": "true",
                            "relation_correct": "true",
                            "url_opened": "true",
                            "note": "reviewed",
                        }
                    )
            with self.assertRaisesRegex(ValueError, "중복"):
                MODULE.import_labels(
                    "123e4567-e89b-12d3-a456-426614174000",
                    "reviewer-1",
                    source,
                    True,
                )

    def test_operational_run_count_only_accepts_healthy_completed_general_runs(self):
        metrics_json = {
            "sample_status": "READY",
            "source_snapshot_count": 18,
            "elapsed_hours": 24,
            "healthy_general_runs": 4,
            "relevance_reviewed": 180,
            "relevance_correct": 180,
            "category_metrics": {category: [30, 30] for category in MODULE.CATEGORIES},
            "relation_metrics": {
                "DIRECT_COMPLEX": [60, 60],
                "SAME_DONG": [40, 40],
                "SAME_SIGUNGU": [40, 40],
            },
            "challenge_reviewed": 50,
            "challenge_false_direct": 0,
            "url_reviewed": 100,
            "url_opened": 100,
        }
        with mock.patch.object(MODULE, "run_psql", return_value=__import__("json").dumps(metrics_json)) as run_psql:
            MODULE.load_metrics("123e4567-e89b-12d3-a456-426614174000")
        sql = run_psql.call_args.args[0]
        self.assertIn("execution.execution_type = 'GENERAL'", sql)
        self.assertIn("execution.state = 'COMPLETED'", sql)
        self.assertIn("execution.truncated_work_unit_count = 0", sql)
        self.assertNotIn("execution.execution_type IN ('GENERAL', 'BOOTSTRAP')", sql)

    def test_private_export_refuses_repository_path(self):
        with self.assertRaisesRegex(ValueError, "repository 밖"):
            MODULE.ensure_private_output(MODULE.REPOSITORY_ROOT / "tmp" / "review.csv", False)

    def test_external_csv_cells_cannot_become_spreadsheet_formulas(self):
        self.assertEqual("'=HYPERLINK(\"https://evil.test\")", MODULE.spreadsheet_safe('=HYPERLINK("https://evil.test")'))
        self.assertEqual("'@SUM(1,1)", MODULE.spreadsheet_safe("@SUM(1,1)"))
        self.assertEqual("일반 기사 제목", MODULE.spreadsheet_safe("일반 기사 제목"))


if __name__ == "__main__":
    unittest.main()
