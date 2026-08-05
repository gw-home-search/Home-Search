from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import h5py
import numpy as np

try:
    import tensorflow as tf

    from ml_service.f37_predictor import F37Predictor
    from ml_service.smoke_predict import assert_sample_prediction_quality
except ModuleNotFoundError:
    tf = None
    F37Predictor = None
    assert_sample_prediction_quality = None


@unittest.skipIf(tf is None, "ML runtime dependencies are verified in the built image")
class F37PredictorCompatibilityTest(unittest.TestCase):
    def test_legacy_keras_module_failure_uses_archive_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact_dir = Path(temp_dir)
            (artifact_dir / "keras_model.keras").touch()
            predictor = object.__new__(F37Predictor)
            predictor.artifact_dir = artifact_dir
            fallback_model = object()

            with (
                patch.object(
                    tf.keras.models,
                    "load_model",
                    side_effect=TypeError(
                        "Could not deserialize keras.src.engine.functional.Functional"
                    ),
                ),
                patch.object(
                    predictor,
                    "_load_model_from_weights_archive",
                    return_value=fallback_model,
                ) as fallback,
            ):
                self.assertIs(predictor._load_model(), fallback_model)

            fallback.assert_called_once_with(artifact_dir / "keras_model.keras")

    def test_unknown_model_type_error_is_not_hidden(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact_dir = Path(temp_dir)
            (artifact_dir / "keras_model.keras").touch()
            predictor = object.__new__(F37Predictor)
            predictor.artifact_dir = artifact_dir

            with patch.object(
                tf.keras.models,
                "load_model",
                side_effect=TypeError("unrelated model defect"),
            ):
                with self.assertRaisesRegex(TypeError, "unrelated model defect"):
                    predictor._load_model()

    def test_legacy_vocabulary_is_read_from_the_model_archive(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            model_path = Path(temp_dir) / "keras_model.keras"
            with zipfile.ZipFile(model_path, "w") as archive:
                archive.writestr(
                    "assets/_layer_checkpoint_dependencies/string_lookup/vocabulary.txt",
                    "[UNK]\n1111010100\n",
                )

            predictor = object.__new__(F37Predictor)

            self.assertEqual(
                predictor._load_vocabulary(model_path, "legal_dong_code"),
                ["[UNK]", "1111010100"],
            )

    def test_model_inputs_are_all_tensors(self) -> None:
        predictor = object.__new__(F37Predictor)
        predictor.numeric_features = ["area_m2"]
        predictor.embedding_features = ["legal_dong_code"]
        predictor.medians = {"area_m2": 84.5}

        inputs = predictor.make_inputs({}, {"legal_dong_code": "1111010100"})

        self.assertTrue(all(tf.is_tensor(value) for value in inputs.values()))

    def test_archive_fallback_finalizes_normalization_after_assigning_weights(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            weights_path = Path(temp_dir) / "model.weights.h5"
            with h5py.File(weights_path, "w") as weights:
                dependencies = weights.create_group("_layer_checkpoint_dependencies")
                for group_name in ["embedding", "embedding_2", "embedding_4"]:
                    variables = dependencies.create_group(group_name).create_group("vars")
                    variables.create_dataset("0", data=np.zeros((1, 1), dtype="float32"))
                normalization_variables = dependencies.create_group("normalization").create_group("vars")
                normalization_variables.create_dataset("0", data=np.zeros((1,), dtype="float32"))
                normalization_variables.create_dataset("1", data=np.ones((1,), dtype="float32"))
                normalization_variables.create_dataset("2", data=np.array(1, dtype="int64"))
                for group_name in ["dense", "dense_2", "dense_4"]:
                    variables = dependencies.create_group(group_name).create_group("vars")
                    variables.create_dataset("0", data=np.zeros((1, 1), dtype="float32"))
                    variables.create_dataset("1", data=np.zeros((1,), dtype="float32"))

            model_path = Path(temp_dir) / "keras_model.keras"
            with zipfile.ZipFile(model_path, "w") as archive:
                archive.write(weights_path, "model.weights.h5")

            predictor = object.__new__(F37Predictor)
            embedding_layers = [MagicMock(), MagicMock(), MagicMock()]
            normalization = MagicMock()
            dense_layers = [MagicMock(), MagicMock(), MagicMock()]

            predictor._assign_archive_weights(
                model_path,
                embedding_layers=embedding_layers,
                normalization=normalization,
                dense_layers=dense_layers,
            )

            normalization.finalize_state.assert_called_once_with()

    def test_smoke_rejects_sample_prediction_beyond_recent_holdout_p99(self) -> None:
        payload = {"actual_price_per_m2": 2030.9364}
        metadata = {"metrics": [{"split": "recent_holdout", "abs_pct_error_p99": 0.353809}]}

        with self.assertRaisesRegex(RuntimeError, "exceeds the recent_holdout p99 error"):
            assert_sample_prediction_quality(
                payload,
                {"predictedPricePerM2": 7145.0466},
                metadata,
            )

        assert_sample_prediction_quality(
            payload,
            {"predictedPricePerM2": 2039.4983},
            metadata,
        )


if __name__ == "__main__":
    unittest.main()
